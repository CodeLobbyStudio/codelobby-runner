require "minitest/autorun"
require "open3"
require "tmpdir"
require "fileutils"
module Rails
  def self.application
    self
  end
  def self.config
    self
  end
  def self.to_prepare(&block)
  end
end
require File.join(File.dirname(ARGV.fetch(0)), "sandbox-slots")
require ARGV.fetch(0)

class WorkspaceBase
  attr_reader :box_id, :workdir, :boxdir, :tmpdir, :events
  attr_accessor :fail_prepare, :fail_cleanup, :fail_verify, :fail_original_cleanup, :fail_init, :forced_path

  def initialize(pool)
    @pool = pool
    @events = []
  end

  def perform(mode = :normal)
    initialize_workdir
    raise Interrupt, "Synthetic process interruption" if mode == :interrupted
    cleanup
  end

  def initialize_workdir
    raise "Synthetic early initialization failure" if fail_init
    @box_id = 1007
    @workdir = forced_path || "/var/local/lib/isolate/#{box_id}"
    @boxdir = workdir + "/box"
    @tmpdir = workdir + "/tmp"
    initialize_file("stdin")
    initialize_file("stdout")
  end

  def initialize_file(file)
    events << [:file, file]
  end

  def cleanup(*args)
    events << [:original_cleanup, *args]
    raise "Synthetic Isolate cleanup failure" if fail_original_cleanup
  end
end

class WorkspaceJob < WorkspaceBase
  prepend CodeLobbyBoundedWorkspace
end

class WorkspaceHarness < WorkspaceJob
  private

  def sandbox_pool
    @pool
  end

  def workspace_operation(*args)
    events << args
    failure = (args.first == "prepare" && fail_prepare) || (args.first == "cleanup" && fail_cleanup)
    failure ||= args.first == "verify-released" && fail_verify
    raise "Synthetic helper failure" if failure
  end
end

class BoundedWorkspaceTest < Minitest::Test
  def setup
    @names = ["WORKSPACE_SIZE_MIB", "WORKSPACE_INODES", "COUNT"]
    @saved = ENV.to_h.select { |key, _| @names.include?(key) }
    @names.each { |name| ENV.delete(name) }
    @root = Dir.mktmpdir("codelobby-workspace-test-")
    @pool = CodeLobbySandboxSlots::Pool.new(@root, size: 2)
    @jobs = []
    @leases = []
  end

  def teardown
    @jobs.each do |job|
      lease = job.instance_variable_get(:@codelobby_sandbox_lease)
      lease.quarantine if lease
    end
    @leases.each(&:quarantine)
    @names.each { |name| ENV.delete(name) }
    @saved.each { |key, value| ENV[key] = value }
    FileUtils.remove_entry(@root)
  end

  def job
    value = WorkspaceHarness.new(@pool)
    @jobs << value
    value
  end

  def next_lease
    lease = @pool.acquire
    @leases << lease
    lease
  end

  def test_reserves_before_initialization_and_prepares_once
    value = job
    assert_raises(RuntimeError) { value.box_id }
    value.send(:initialize_workdir)
    assert_equal 0, value.box_id
    assert_equal ["verify-released", "0"], value.events.first
    assert_equal ["prepare", "0", "64", "4096"], value.events[1]
    assert_equal 1, value.events.count { |event| event.first == "prepare" }
    assert_equal [:file, "stdin"], value.events[2]
    assert_equal 1, next_lease.id
  end

  def test_releases_only_after_unmount_isolate_cleanup_and_confirmation
    value = job
    value.send(:initialize_workdir)
    value.send(:cleanup, false)
    assert_equal [["cleanup", "0"], [:original_cleanup, false], ["verify-released", "0"]], value.events.last(3)
    assert_equal 0, next_lease.id
  end

  def test_prepare_failure_blocks_source_writes_and_quarantines_slot
    value = job
    value.fail_prepare = true
    assert_raises(RuntimeError) { value.send(:initialize_workdir) }
    refute value.events.any? { |event| event.first == :file }
    value.fail_verify = true
    value.send(:cleanup, false)
    assert_equal 1, next_lease.id
  end

  def test_workspace_cleanup_failure_blocks_original_cleanup_and_reuse
    value = job
    value.send(:initialize_workdir)
    value.fail_cleanup = true
    assert_raises(RuntimeError) { value.send(:cleanup) }
    refute value.events.any? { |event| event.first == :original_cleanup }
    count = value.events.size
    value.send(:cleanup, false)
    assert_equal count, value.events.size
    assert_raises(RuntimeError) { value.send(:initialize_workdir) }
    assert_equal 1, next_lease.id
  end

  def test_original_cleanup_failure_does_not_release_slot
    value = job
    value.send(:initialize_workdir)
    value.fail_original_cleanup = true
    value.send(:cleanup, false)
    assert_equal 1, value.events.count { |event| event.first == "verify-released" }
    assert_equal 1, next_lease.id
  end

  def test_stale_workspace_or_cgroup_blocks_initialization_and_quarantines_slot
    value = job
    value.fail_verify = true
    assert_raises(RuntimeError) { value.send(:initialize_workdir) }
    value.send(:cleanup, false)
    assert_equal [["verify-released", "0"]], value.events
    assert_equal 1, next_lease.id
  end

  def test_missing_cleanup_confirmation_does_not_release_slot
    value = job
    value.send(:initialize_workdir)
    value.fail_verify = true
    assert_raises(RuntimeError) { value.send(:cleanup) }
    assert_equal 1, next_lease.id
  end

  def test_initialization_failure_does_not_run_cleanup_with_missing_paths
    value = job
    value.fail_init = true
    assert_raises(RuntimeError) { value.send(:initialize_workdir) }
    value.send(:cleanup, false)
    assert_equal [["verify-released", "0"]], value.events
    assert_equal 1, next_lease.id
  end

  def test_no_free_slot_blocks_initialization_and_does_not_clean_another_job
    first = next_lease
    second = next_lease
    value = job
    assert_raises(CodeLobbySandboxSlots::Unavailable) { value.send(:initialize_workdir) }
    value.send(:cleanup, false)
    assert_empty value.events
    first.release
    assert_equal 0, next_lease.id
    assert_equal 1, second.id
  end

  def test_unexpected_exit_keeps_slot_quarantined
    value = job
    assert_raises(Interrupt) { value.perform(:interrupted) }
    assert_equal 1, next_lease.id
  end

  def test_normal_perform_releases_the_reservation
    job.perform
    assert_equal 0, next_lease.id
  end

  def test_invalid_settings_are_rejected_before_reservation
    ["0", "-1", "4; echo x", "257", "", "4.5"].each do |setting|
      ENV["WORKSPACE_SIZE_MIB"] = setting
      value = job
      assert_raises(RuntimeError) { value.send(:initialize_workdir) }
      assert_empty value.events
    end
    assert_equal 0, next_lease.id
  end

  def test_inode_bounds
    ["127", "16385", "unlimited"].each do |setting|
      ENV["WORKSPACE_INODES"] = setting
      assert_raises(RuntimeError) { job.send(:initialize_workdir) }
    end
  end

  def test_does_not_enable_multiple_workers_in_this_stage
    ENV["COUNT"] = "2"
    assert_raises(RuntimeError) { job.send(:initialize_workdir) }
    assert_equal 0, next_lease.id
  end

  def test_mismatched_path_is_not_passed_to_cleanup
    value = job
    value.forced_path = "/tmp/0"
    assert_raises(RuntimeError) { value.send(:initialize_workdir) }
    value.send(:cleanup, false)
    refute value.events.any? { |event| event.first == :original_cleanup }
    assert_equal 1, next_lease.id
  end

  def test_cleanup_without_reservation_is_a_noop
    value = job
    value.send(:cleanup, false)
    assert_empty value.events
  end

  def test_root_helper_rejects_path_injection_before_privileged_operations
    helper = File.join(File.dirname(ARGV.fetch(0)), "workspace-helper.sh")
    ["../7", "007", "1000", "7;id", "-1"].each do |id|
      ["prepare", "cleanup", "verify-released"].each do |operation|
        args = [operation, id]
        args += ["64", "4096"] if operation == "prepare"
        _out, err, result = Open3.capture3("/bin/bash", helper, *args)
        refute result.success?
        assert_includes err, "box-id"
      end
    end
  end

  def test_root_helper_rejects_invalid_limits_before_privileged_operations
    helper = File.join(File.dirname(ARGV.fetch(0)), "workspace-helper.sh")
    [["0", "4096", "size"], ["257", "4096", "size"], ["64", "127", "inodes"], ["64", "16385", "inodes"]].each do |size, inodes, error|
      _out, err, result = Open3.capture3("/bin/bash", helper, "prepare", "0", size, inodes)
      refute result.success?
      assert_includes err, error
    end
  end
end
