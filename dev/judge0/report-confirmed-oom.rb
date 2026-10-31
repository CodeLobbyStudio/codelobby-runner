# Local adapter for the pinned Mac image. No grading or execution changes.
require "digest"

module CodeLobbyConfirmedOom
  private

  def run
    @runtime_oom_kills_before = runtime_oom_kill_count
    super
  end

  def verify
    super
    metadata = get_metadata
    # Never infer OOM from exit 137, stderr, or reported peak memory alone.
    return unless cgroups.present? && metadata[:"cg-oom-killed"].to_s.strip == "1"
    return if ["TO", "XX"].include?(metadata[:status])
    after = runtime_oom_kill_count
    return unless @runtime_oom_kills_before && after && after > @runtime_oom_kills_before

    submission.status = Status.other
    submission.message = "Memory limit exceeded"
  end

  def runtime_oom_kill_count
    return unless cgroups.present?
    root = File.read("/run/isolate/cgroup").strip
    line = File.readlines(File.join(root, "box-#{Integer(box_id)}", "memory.events"))
               .find { |entry| entry.split.first == "oom_kill" }
    Integer(line.split.fetch(1), 10) if line
  rescue SystemCallError, ArgumentError, IndexError
    # Missing/unreadable evidence must not be guessed from an exit code.
    nil
  end
end

Rails.application.config.to_prepare do
  source = Rails.root.join("app/jobs/isolate_job.rb")
  expected = "61e8aab9d40ada8caf2df9a2e381395ff04cd1415a3c6b3d0d7638178d9c29a6"
  unless Digest::SHA256.file(source).hexdigest == expected
    raise "Review the OOM adapter before using a different Judge0 job implementation"
  end
  IsolateJob.prepend(CodeLobbyConfirmedOom) unless IsolateJob.ancestors.include?(CodeLobbyConfirmedOom)
end
