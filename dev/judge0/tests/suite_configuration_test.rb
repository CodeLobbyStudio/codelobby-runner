require "minitest/autorun"
require "minitest/mock"
module Rails
  def self.application; self; end
  def self.config; self; end
  def self.middleware; self; end
  def self.use(*); end
  def self.to_prepare; end
end
require ARGV.fetch(0)

class ConfigurationBase
  def get_metadata; {time: "0.1", "time-wall": "0.2"}; end
end
class ConfigurationJob < ConfigurationBase
  prepend CodeLobbySuiteReport
  def initialize
    @codelobby_compiling = true
  end
  def box_id; 7; end
  def submission; Struct.new(:is_project, :token).new(true, "11111111-2222-3333-4444-555555555555"); end
end

class SuiteConfigurationTest < Minitest::Test
  Result = Struct.new(:exitstatus) do
    def success?; exitstatus == 0; end
  end

  def capture(job, marker_status)
    stored = []
    redis = Object.new
    redis.define_singleton_method(:eval) { |_, keys:, argv:| stored << JSON.parse(argv.last) }
    helper = lambda do |*args, **options|
      assert_equal ["/usr/bin/sudo", "-n", "/usr/local/ruby-2.7.0/bin/ruby",
                    "/usr/local/bin/read-suite-report.rb", "7", "configuration-error"], args
      ["", Result.new(marker_status)]
    end
    CodeLobbySuiteReport.stub(:redis, redis) do
      Open3.stub(:capture2, helper) { job.send(:get_metadata) }
    end
    stored
  end

  def test_captures_configuration_failure_before_terminal_compilation_status
    stored = capture(ConfigurationJob.new, 0)
    assert_equal true, stored.last["configurationError"]
    assert_equal "MISSING", stored.last["state"]
    assert_equal 100.0, stored.last["compilation"]["cpuTimeMs"]
  end

  def test_missing_marker_does_not_claim_a_configuration_failure
    assert_equal false, capture(ConfigurationJob.new, 2).last["configurationError"]
  end

  def test_helper_failure_cannot_silently_become_a_participant_error
    assert_raises(RuntimeError) { capture(ConfigurationJob.new, 1) }
  end

  def test_runtime_metadata_does_not_read_a_participant_written_marker
    job = ConfigurationJob.new
    job.instance_variable_set(:@codelobby_compiling, false)
    Open3.stub(:capture2, ->(*) { flunk "Runtime must not recapture the marker" }) do
      job.send(:get_metadata)
    end
  end
end
