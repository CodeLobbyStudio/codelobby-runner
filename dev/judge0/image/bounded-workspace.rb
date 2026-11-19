# Per-submission /box + /tmp capacity for the pinned experimental Mac image.
require "digest"
require "/usr/local/lib/codelobby/sandbox-slots" unless defined?(CodeLobbySandboxSlots)

module CodeLobbyBoundedWorkspace
  HELPER = "/usr/local/bin/workspace-helper.sh"

  def self.setting(name, default, range)
    raw = ENV.fetch(name, default.to_s)
    raise "Invalid bounded workspace setting" unless /\A[1-9][0-9]*\z/.match?(raw)
    value = Integer(raw, 10)
    raise "Invalid bounded workspace setting" unless range.cover?(value)
    value
  end

  def perform(*args)
    super
  ensure
    # Unexpected exits retain the occupied marker. Process death releases flock only.
    @codelobby_sandbox_lease.quarantine if @codelobby_sandbox_lease
  end

  def box_id
    raise "Sandbox slot has not been reserved" unless @codelobby_sandbox_lease
    @codelobby_sandbox_lease.id
  end

  private

  def initialize_workdir
    CodeLobbyBoundedWorkspace.setting("COUNT", 1, 1..2)
    raise "Previous sandbox reservation was not released" if @codelobby_sandbox_lease
    @bounded_workspace_size = CodeLobbyBoundedWorkspace.setting("WORKSPACE_SIZE_MIB", 64, 4..256)
    @bounded_workspace_inodes = CodeLobbyBoundedWorkspace.setting("WORKSPACE_INODES", 4096, 128..16384)
    @codelobby_sandbox_lease = sandbox_pool.acquire(owner: @codelobby_owner_token)
    @codelobby_journal.assign(box_id) if @codelobby_journal
    # Also reject stale artifacts when a container was recreated without its reservation files.
    workspace_operation("verify-released", box_id.to_s)
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
    # Judge0 may call cleanup(false) even when initialization failed before paths existed.
    return unless @codelobby_sandbox_lease
    return if @codelobby_sandbox_cleanup_failed
    begin
      expected = "/var/local/lib/isolate/#{box_id}"
      unless workdir == expected && boxdir == expected + "/box" && tmpdir == expected + "/tmp"
        raise "Unverified sandbox cleanup paths"
      end
      if @bounded_workspace_prepared
        workspace_operation("cleanup", box_id.to_s)
        @bounded_workspace_prepared = false
      end
      super
      workspace_operation("verify-released", box_id.to_s)
      @codelobby_sandbox_lease.release
      @codelobby_sandbox_lease = nil
    rescue StandardError
      @codelobby_sandbox_cleanup_failed = true
      @codelobby_sandbox_lease.quarantine
      raise unless args.first == false
    end
  end

  def sandbox_pool
    CodeLobbySandboxSlots::Pool.new
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
