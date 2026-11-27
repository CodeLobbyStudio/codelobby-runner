require "minitest/autorun"
module Rails
  def self.application; self; end
  def self.config; self; end
  def self.middleware; self; end
  def self.use(*); end
end
class Redis
  class BaseError < StandardError; end
end
module CodeLobbySuiteReport
  class << self
    attr_accessor :redis
  end
end
require ARGV.fetch(0)

class ArtifactRedis
  attr_reader :calls
  attr_accessor :body, :fail
  def initialize; @calls = []; end
  def get(key)
    raise Redis::BaseError if @fail
    @calls << [:get, key]
    @body
  end
  def multi; yield self; end
  def del(key); @calls << [:del, key]; end
  def zrem(index, key); @calls << [:zrem, index, key]; end
end

class JavaArtifactEndpointTest < Minitest::Test
  TOKEN = "11111111-2222-3333-4444-555555555555"
  KEY = "x" * 64

  def setup
    @old = ENV["CODELOBBY_ARTIFACT_KEY"]
    ENV["CODELOBBY_ARTIFACT_KEY"] = KEY
    @redis = ArtifactRedis.new
    CodeLobbySuiteReport.redis = @redis
    @endpoint = CodeLobbyJavaArtifactEndpoint::Endpoint.new(->(_) { [204, {}, []] })
  end

  def teardown
    ENV["CODELOBBY_ARTIFACT_KEY"] = @old
  end

  def call(method = "GET", key = KEY, token = TOKEN)
    @endpoint.call("PATH_INFO" => "/codelobby/v1/java-artifacts/#{token}",
                   "REQUEST_METHOD" => method, "HTTP_X_CODELOBBY_ARTIFACT_KEY" => key)
  end

  def test_requires_the_private_key_before_store_access
    [nil, "", "wrong", KEY + "," + KEY, "x" * 257].each do |key|
      response = call("GET", key)
      assert_equal 401, response.first
      assert_equal "no-store", response[1]["Cache-Control"]
      refute_includes response.last.join, KEY
    end
    assert_empty @redis.calls
  end

  def test_missing_configuration_fails_closed
    ENV.delete("CODELOBBY_ARTIFACT_KEY")
    @endpoint = CodeLobbyJavaArtifactEndpoint::Endpoint.new(->(_) { [204, {}, []] })
    assert_equal 503, call.first
    assert_empty @redis.calls
  end

  def test_returns_only_the_requested_record_and_handles_expiry
    @redis.body = '{"state":"AVAILABLE","snapshot":"private-bytes","sha256":"hash"}'
    response = call
    assert_equal 200, response.first
    assert_equal @redis.body, response.last.join
    assert_equal [[:get, CodeLobbyJavaArtifactEndpoint::PREFIX + TOKEN]], @redis.calls
    @redis.body = nil
    assert_equal({"state" => "MISSING"}, JSON.parse(call.last.join))
  end

  def test_delete_removes_record_and_index_entry
    assert_equal({"state" => "DELETED"}, JSON.parse(call("DELETE").last.join))
    assert_equal [[:del, CodeLobbyJavaArtifactEndpoint::PREFIX + TOKEN],
                  [:zrem, CodeLobbyJavaArtifactEndpoint::PREFIX + "index", CodeLobbyJavaArtifactEndpoint::PREFIX + TOKEN],
                  [:del, CodeLobbyJavaArtifactEndpoint::CLEANUP_PREFIX + TOKEN],
                  [:zrem, CodeLobbyJavaArtifactEndpoint::CLEANUP_PREFIX + "index", CodeLobbyJavaArtifactEndpoint::CLEANUP_PREFIX + TOKEN]], @redis.calls
  end

  def test_rejects_invalid_tokens_and_methods
    assert_equal 404, call("GET", KEY, "../secret").first
    assert_equal 405, call("POST").first
    assert_empty @redis.calls
  end

  def test_oversize_or_unavailable_store_never_exposes_bytes
    @redis.body = "private" * 300000
    assert_equal 503, call.first
    @redis.fail = true
    assert_equal 503, call.first
    refute_includes call.last.join, "private"
  end

  def test_cleanup_receipt_is_private_and_missing_receipts_remain_pending
    assert_equal 401, call("GET", "wrong", TOKEN + "/cleanup").first
    assert_empty @redis.calls
    assert_equal({"state" => "PENDING"}, JSON.parse(call("GET", KEY, TOKEN + "/cleanup").last.join))
    @redis.body = '{"state":"CLEANED_UP"}'
    assert_equal({"state" => "CLEANED_UP"}, JSON.parse(call("GET", KEY, TOKEN + "/cleanup").last.join))
    assert_equal [:get, CodeLobbyJavaArtifactEndpoint::CLEANUP_PREFIX + TOKEN], @redis.calls.last
    assert_equal 405, call("DELETE", KEY, TOKEN + "/cleanup").first
  end

  def test_other_routes_are_untouched
    assert_equal 204, @endpoint.call("PATH_INFO" => "/about").first
  end
end
