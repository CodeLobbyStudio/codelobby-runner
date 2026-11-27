require "minitest/autorun"
require "minitest/mock"
module Rails
  def self.application; self; end
  def self.config; self; end
  def self.to_prepare; end
  def self.logger; self; end
  def self.warn(message)
    raise "Unsafe diagnostic" unless ["Java artifact capture unavailable", "Execution cleanup confirmation unavailable"].include?(message)
  end
end
require ARGV.fetch(0)

class ArtifactBase
  attr_reader :events
  def initialize(result = :success)
    @events = []
    @result = result
    @codelobby_compilation_started = true
  end
  def compile; @events << :compile; @result; end
  def run; @events << :run; end
  def cleanup(*)
    raise "cleanup not verified" if @cleanup_failure
    @codelobby_sandbox_lease = nil unless @retain_lease
    :cleaned
  end
end
class ArtifactJob < ArtifactBase
  prepend CodeLobbyJavaArtifactCapture
  def submission; Struct.new(:is_project, :token).new(true, "11111111-2222-3333-4444-555555555555"); end
  def box_id; 7; end
end

class JavaArtifactCaptureTest < Minitest::Test
  Result = Struct.new(:exitstatus)

  def setup
    @previous = ENV["CODELOBBY_CAPTURE_JAVA_ARTIFACTS"]
    ENV["CODELOBBY_CAPTURE_JAVA_ARTIFACTS"] = "true"
  end

  def teardown
    ENV["CODELOBBY_CAPTURE_JAVA_ARTIFACTS"] = @previous
  end

  def capture(job, status, bytes = '{"version":1,"files":[]}')
    stored = []
    redis = Object.new
    redis.define_singleton_method(:eval) do |script, keys:, argv:|
      stored << [script, keys, JSON.parse(argv.last)]
    end
    helper = lambda do |*args, **options|
      assert_equal "java-artifacts", args.last
      assert_equal true, options[:binmode]
      assert_equal File::NULL, options[:err]
      job.events << :capture
      [bytes, Result.new(status)]
    end
    CodeLobbyJavaArtifactCapture.stub(:redis, redis) do
      Open3.stub(:capture2, helper) do
        assert_equal :success, job.send(:compile)
        job.send(:run)
      end
    end
    stored
  end

  def test_captures_only_after_compile_and_before_run
    job = ArtifactJob.new
    stored = capture(job, 0)
    assert_equal [:compile, :capture, :run], job.events
    assert_equal "AVAILABLE", stored.first.last["state"]
    bytes = Base64.strict_decode64(stored.first.last["snapshot"])
    assert_equal Digest::SHA256.hexdigest(bytes), stored.first.last["sha256"]
    assert_equal CodeLobbyJavaArtifactCapture::PREFIX + job.submission.token, stored.first[1].last
    assert_includes stored.first[0], "'EX', 300"
    assert_includes stored.first[0], "- 32"
  end

  def test_does_not_capture_failed_compilations
    job = ArtifactJob.new(:failure)
    Open3.stub(:capture2, ->(*) { flunk "Failed compilation must not be captured" }) do
      assert_equal :failure, job.send(:compile)
    end
  end

  def test_does_not_recapture_replayed_classes_without_a_compiler_phase
    job = ArtifactJob.new
    job.instance_variable_set(:@codelobby_compilation_started, false)
    Open3.stub(:capture2, ->(*) { flunk "A replay must not create another artifact snapshot" }) do
      assert_equal :success, job.send(:compile)
    end
  end

  def test_is_disabled_without_explicit_opt_in
    ENV.delete("CODELOBBY_CAPTURE_JAVA_ARTIFACTS")
    job = ArtifactJob.new
    Open3.stub(:capture2, ->(*) { flunk "Default behavior must remain unchanged" }) do
      assert_equal :success, job.send(:compile)
    end
  end

  def test_helper_failures_do_not_change_evaluation_or_store_partial_bytes
    {1 => "UNAVAILABLE", 2 => "MISSING", 3 => "TOO_LARGE"}.each do |status, expected|
      stored = capture(ArtifactJob.new, status, "private-partial-bytes")
      assert_equal({"state" => expected}, stored.first.last)
    end
  end

  def test_rejects_oversized_helper_output_without_affecting_success
    assert_empty capture(ArtifactJob.new, 0, "a" * 1_500_001)
  end

  def test_cleanup_receipt_requires_a_previously_held_and_released_lease
    [false, true].each do |retain|
      job = ArtifactJob.new
      job.instance_variable_set(:@codelobby_sandbox_lease, Object.new)
      job.instance_variable_set(:@retain_lease, retain)
      stored = []
      redis = Object.new
      redis.define_singleton_method(:eval) { |_, keys:, argv:| stored << [keys, JSON.parse(argv.last)] }
      CodeLobbyJavaArtifactCapture.stub(:redis, redis) { assert_equal :cleaned, job.send(:cleanup) }
      if retain
        assert_empty stored
      else
        assert_equal "CLEANED_UP", stored.first.last["state"]
        assert_equal CodeLobbyJavaArtifactCapture::CLEANUP_PREFIX + job.submission.token, stored.first.first.last
      end
    end
  end

  def test_failed_cleanup_never_publishes_a_receipt
    job = ArtifactJob.new
    job.instance_variable_set(:@codelobby_sandbox_lease, Object.new)
    job.instance_variable_set(:@cleanup_failure, true)
    CodeLobbyJavaArtifactCapture.stub(:redis, -> { flunk "Cleanup failure must not publish" }) do
      assert_raises(RuntimeError) { job.send(:cleanup) }
    end
  end

  def test_no_lease_does_not_claim_cleanup
    job = ArtifactJob.new
    CodeLobbyJavaArtifactCapture.stub(:redis, -> { flunk "No lease must not publish a receipt" }) do
      assert_equal :cleaned, job.send(:cleanup)
    end
  end

  def test_receipt_store_failure_does_not_replace_verified_cleanup
    job = ArtifactJob.new
    job.instance_variable_set(:@codelobby_sandbox_lease, Object.new)
    redis = Object.new
    redis.define_singleton_method(:eval) { |*args, **options| raise "private-storage-error" }
    CodeLobbyJavaArtifactCapture.stub(:redis, redis) { assert_equal :cleaned, job.send(:cleanup) }
  end

  def test_redis_failure_does_not_replace_successful_evaluation
    redis = Object.new
    redis.define_singleton_method(:eval) { |*args, **options| raise "private-redis-error" }
    CodeLobbyJavaArtifactCapture.stub(:redis, redis) do
      Open3.stub(:capture2, ["{}", Result.new(0)]) do
        assert_equal :success, ArtifactJob.new.send(:compile)
      end
    end
  end
end
