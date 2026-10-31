# Per-submission /box + /tmp capacity for the pinned experimental Mac image.
require "digest"

module CodeLobbyBoundedWorkspace
  HELPER = "/usr/local/bin/workspace-helper.sh"

  def self.setting(name, default, range)
    raw = ENV.fetch(name, default.to_s)
    raise "Invalid bounded workspace setting" unless /\A[1-9][0-9]*\z/.match?(raw)
    value = Integer(raw, 10)
    raise "Invalid bounded workspace setting" unless range.cover?(value)
    value
  end

  # This package deliberately runs one worker. Reuse Isolate's 1000 box IDs
  # after each submission is cleaned up instead of exhausting them after 999 jobs.
  def box_id
    Integer(super) % 1000
  end

  private

  def initialize_workdir
    CodeLobbyBoundedWorkspace.setting("COUNT", 1, 1..1)
    raise "Previous bounded workspace was not released" if @bounded_workspace_prepared
    @bounded_workspace_size = CodeLobbyBoundedWorkspace.setting("WORKSPACE_SIZE_MIB", 64, 4..256)
    @bounded_workspace_inodes = CodeLobbyBoundedWorkspace.setting("WORKSPACE_INODES", 4096, 128..16384)
    super
  end

  def initialize_file(file)
    unless @bounded_workspace_prepared
      id = Integer(box_id)
      raise "Unsupported bounded workspace box ID" unless (0..999).cover?(id)
      raise "Unexpected bounded workspace path" unless workdir == "/var/local/lib/isolate/#{id}"
      workspace_operation("prepare", id.to_s, @bounded_workspace_size.to_s, @bounded_workspace_inodes.to_s)
      @bounded_workspace_prepared = true
    end
    super
  end

  def cleanup(*args)
    if @bounded_workspace_prepared
      # Unmount before Isolate removes its directories. Never use lazy unmount.
      workspace_operation("cleanup", Integer(box_id).to_s)
      @bounded_workspace_prepared = false
    end
    super
  end

  def workspace_operation(*args)
    ok = system("/usr/bin/sudo", "/bin/bash", HELPER, *args,
                out: File::NULL, err: File::NULL)
    raise "Could not prepare or release bounded workspace" unless ok
  end
end

Rails.application.config.to_prepare do
  source = Rails.root.join("app/jobs/isolate_job.rb")
  expected = "61e8aab9d40ada8caf2df9a2e381395ff04cd1415a3c6b3d0d7638178d9c29a6"
  raise "Review bounded workspace adapter for this Judge0 version" unless Digest::SHA256.file(source).hexdigest == expected
  CodeLobbyBoundedWorkspace.setting("WORKSPACE_SIZE_MIB", 64, 4..256)
  CodeLobbyBoundedWorkspace.setting("WORKSPACE_INODES", 4096, 128..16384)
  IsolateJob.prepend(CodeLobbyBoundedWorkspace) unless IsolateJob.ancestors.include?(CodeLobbyBoundedWorkspace)
end
