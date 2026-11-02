require "minitest/autorun"
require "ostruct"

# Isolate the verification hook from Rails and the database.
module Rails
  def self.application
    self
  end
  def self.config
    self
  end
  def self.to_prepare(&block)
    # Boot/checksum behavior is exercised by starting the real worker.
  end
end

module Status
  def self.other
    :other
  end
end

class String
  def present?
    !empty?
  end
end

require ARGV.fetch(0)

class FakeJob
  attr_reader :submission, :metadata, :cgroups

  def initialize(metadata, cgroups = "--cg", counts = [0, 1])
    @counts = counts.dup
    @metadata = metadata
    @cgroups = cgroups
    @submission = OpenStruct.new(status: :original, message: "Original error")
  end

  def run
  end

  def runtime_oom_kill_count
    @counts.shift
  end

  def verify
    @original_called = true
  end

  def get_metadata
    raise "Original verification must run first" unless @original_called
    metadata
  end

  prepend CodeLobbyConfirmedOom
end

# Override the host-counter reader after prepend, keeping the actual hook active.
class TestJob < FakeJob
  def runtime_oom_kill_count
    @counts.shift
  end
end

class ConfirmedOomTest < Minitest::Test
  def verify(metadata, cgroups = "--cg", counts = [0, 1])
    job = TestJob.new(metadata, cgroups, counts)
    job.send(:run)
    job.send(:verify)
    job.submission
  end

  def test_confirmed_oom_is_explicit
    result = verify({ :"cg-oom-killed" => "1", status: "RE", exitcode: "137" })
    assert_equal :other, result.status
    assert_equal "Memory limit exceeded", result.message
  end

  def test_exit_137_without_oom_is_not_misclassified
    result = verify({ status: "RE", exitcode: "137", :"cg-mem" => "32000" })
    assert_equal :original, result.status
    assert_equal "Original error", result.message
  end

  def test_missing_zero_or_invalid_marker_is_not_oom
    [nil, "0", "true", "2"].each do |marker|
      assert_equal :original, verify({ :"cg-oom-killed" => marker }).status
    end
  end

  def test_sandbox_failure_and_timeout_keep_their_original_classification
    ["XX", "TO"].each do |status|
      assert_equal :original, verify({ :"cg-oom-killed" => "1", status: status }).status
    end
  end

  def test_cached_compilation_oom_is_not_a_new_runtime_oom
    assert_equal :original, verify({ :"cg-oom-killed" => "1" }, "--cg", [1, 1]).status
  end

  def test_missing_counter_evidence_does_not_infer_oom
    [[nil, 1], [0, nil]].each do |counts|
      assert_equal :original, verify({ :"cg-oom-killed" => "1" }, "--cg", counts).status
    end
  end

  def test_no_cgroups_means_no_cgroup_oom_classification
    assert_equal :original, verify({ :"cg-oom-killed" => "1" }, "").status
  end
end
