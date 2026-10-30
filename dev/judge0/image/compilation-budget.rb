# Experimental phase separation. No shared/global Config constants are modified.
require "digest"

module CodeLobbyCompilationBudget
  RESET_HELPER = "/usr/local/bin/reset-compile-cgroup.sh"

  private

  def compile
    previous = @codelobby_compiling
    @codelobby_compilation_started = false
    @codelobby_compiling = true
    super
  ensure
    @codelobby_compiling = previous
  end

  def `(command)
    if @codelobby_compiling && command.start_with?("isolate ")
      @codelobby_compilation_started = true
      # This environment assignment belongs only to the new shell process.
      super("CODELOBBY_ISOLATE_PHASE=compile " + command)
    else
      super
    end
  end

  def run
    if @codelobby_compilation_started
      # Compile has completed. Refuse to proceed if its cgroup is not empty.
      id = Integer(box_id)
      raise "Unsupported compilation sandbox" unless (0..999).cover?(id)
      ok = system("/usr/bin/sudo", "/bin/bash", RESET_HELPER, id.to_s,
                  out: File::NULL, err: File::NULL)
      raise "Could not reset compilation resource accounting" unless ok
      @codelobby_compilation_started = false
    end
    super
  end
end

Rails.application.config.to_prepare do
  source = Rails.root.join("app/jobs/isolate_job.rb")
  expected = "61e8aab9d40ada8caf2df9a2e381395ff04cd1415a3c6b3d0d7638178d9c29a6"
  raise "Review compilation phase adapter for this image" unless Digest::SHA256.file(source).hexdigest == expected
  IsolateJob.prepend(CodeLobbyCompilationBudget) unless IsolateJob.ancestors.include?(CodeLobbyCompilationBudget)
  # Reset must occur before the OOM hook snapshots the execution counters.
  if defined?(CodeLobbyConfirmedOom) && IsolateJob.ancestors.index(CodeLobbyCompilationBudget) > IsolateJob.ancestors.index(CodeLobbyConfirmedOom)
    raise "Compilation accounting reset must precede execution OOM tracking"
  end
end
