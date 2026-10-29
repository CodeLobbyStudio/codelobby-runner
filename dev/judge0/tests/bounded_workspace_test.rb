require "minitest/autorun"
require "open3"
module Rails
  def self.application; self; end
  def self.config; self; end
  def self.to_prepare(&block); end
end
require ARGV.fetch(0)

class WorkspaceBase
  attr_reader :box_id, :workdir, :events
  attr_accessor :fail_prepare, :fail_cleanup
  def initialize(id = 7, path = "/var/local/lib/isolate/7")
    @box_id, @workdir, @events = id, path, []
  end
  def initialize_workdir
    initialize_file("stdin")
    initialize_file("stdout")
  end
  def initialize_file(file); events << [:file, file]; end
  def cleanup(*args); events << [:original_cleanup, *args]; end
end
class WorkspaceJob < WorkspaceBase
  prepend CodeLobbyBoundedWorkspace
end
class WorkspaceHarness < WorkspaceJob
  private
  def workspace_operation(*args)
    events << args
    raise "helper failure" if (args.first == "prepare" && fail_prepare) || (args.first == "cleanup" && fail_cleanup)
  end
end
class BoundedWorkspaceTest < Minitest::Test
  def setup
    @saved = ENV.to_h.select { |k, _| ["WORKSPACE_SIZE_MIB", "WORKSPACE_INODES"].include?(k) }
    ENV.delete("WORKSPACE_SIZE_MIB")
    ENV.delete("WORKSPACE_INODES")
  end
  def teardown
    ENV.delete("WORKSPACE_SIZE_MIB")
    ENV.delete("WORKSPACE_INODES")
    @saved.each { |k,v| ENV[k] = v }
  end
  def test_prepares_once_before_any_source_files
    job=WorkspaceHarness.new
    job.send(:initialize_workdir)
    assert_equal ["prepare", "7", "64", "4096"], job.events.first
    assert_equal 1, job.events.count { |e| e.first == "prepare" }
    assert_equal [:file, "stdin"], job.events[1]
  end
  def test_unmounts_before_original_cleanup
    job=WorkspaceHarness.new
    job.send(:initialize_workdir)
    job.send(:cleanup, false)
    assert_equal [["cleanup", "7"], [:original_cleanup, false]], job.events.last(2)
  end
  def test_prepare_failure_blocks_file_writes
    job=WorkspaceHarness.new; job.fail_prepare=true
    assert_raises(RuntimeError) { job.send(:initialize_workdir) }
    refute job.events.any? { |e| e.first == :file }
  end
  def test_cleanup_failure_does_not_delete_mounted_data
    job=WorkspaceHarness.new; job.send(:initialize_workdir); job.fail_cleanup=true
    assert_raises(RuntimeError) { job.send(:cleanup) }
    refute job.events.any? { |e| e.first == :original_cleanup }
    assert_raises(RuntimeError) { job.send(:initialize_workdir) }
  end
  def test_invalid_settings_are_rejected_before_preparation
    ["0", "-1", "4; echo x", "257", "", "4.5"].each do |value|
      ENV["WORKSPACE_SIZE_MIB"]=value
      job=WorkspaceHarness.new
      assert_raises(RuntimeError) { job.send(:initialize_workdir) }
      assert_empty job.events
    end
  end
  def test_inode_bounds
    ["127", "16385", "unlimited"].each do |value|
      ENV["WORKSPACE_INODES"]=value
      assert_raises(RuntimeError) { WorkspaceHarness.new.send(:initialize_workdir) }
    end
  end
  def test_box_id_and_path_must_match
    [[1000,"/var/local/lib/isolate/1000"],[7,"/tmp/7"]].each do |id,path|
      job=WorkspaceHarness.new(id,path)
      assert_raises(RuntimeError) { job.send(:initialize_workdir) }
      assert_empty job.events
    end
  end
  def test_cleanup_without_preparation_preserves_original_behavior
    job=WorkspaceHarness.new; job.send(:cleanup)
    assert_equal [[:original_cleanup]],job.events
  end
  def test_root_helper_rejects_path_injection_before_privileged_operations
    helper = File.join(File.dirname(ARGV.fetch(0)), "workspace-helper.sh")
    ["../7", "007", "1000", "7;id", "-1"].each do |id|
      _out, err, result = Open3.capture3("/bin/bash", helper, "prepare", id, "64", "4096")
      refute result.success?
      assert_includes err, "box-id"
    end
  end
  def test_root_helper_rejects_invalid_limits_before_privileged_operations
    helper = File.join(File.dirname(ARGV.fetch(0)), "workspace-helper.sh")
    [["0", "4096", "size"], ["257", "4096", "size"], ["64", "127", "inodes"], ["64", "16385", "inodes"]].each do |size, inodes, error|
      _out, err, result = Open3.capture3("/bin/bash", helper, "prepare", "7", size, inodes)
      refute result.success?
      assert_includes err, error
    end
  end

end
