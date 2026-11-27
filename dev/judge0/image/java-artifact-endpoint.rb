# Optional private transport. Not mounted by the default Compose configuration.
require "digest"
require "json"
require "active_support/security_utils"

module CodeLobbyJavaArtifactEndpoint
  PREFIX = "codelobby:java-artifacts:prototype:v1:"
  CLEANUP_PREFIX = PREFIX + "cleanup:"
  TOKEN = /\A[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\z/

  class Endpoint
    def initialize(app)
      @app = app
      @key = ENV.fetch("CODELOBBY_ARTIFACT_KEY", "")
    end

    def call(env)
      prefix = "/codelobby/v1/java-artifacts/"
      return @app.call(env) unless env["PATH_INFO"].start_with?(prefix)
      return response(503, {state: "UNAVAILABLE"}) unless /\A[A-Za-z0-9_-]{32,256}\z/.match?(@key)
      supplied = env["HTTP_X_CODELOBBY_ARTIFACT_KEY"].to_s
      return response(401, {state: "UNAVAILABLE"}) if supplied.bytesize > 256 ||
        !ActiveSupport::SecurityUtils.secure_compare(Digest::SHA256.digest(@key), Digest::SHA256.digest(supplied))
      parts = env["PATH_INFO"].delete_prefix(prefix).split("/", -1)
      cleanup_request = parts.size == 2 && parts.last == "cleanup"
      token = parts.first.to_s
      return response(404, {state: "UNAVAILABLE"}) unless TOKEN.match?(token) && (parts.size == 1 || cleanup_request)
      if cleanup_request
        return response(405, {state: "UNAVAILABLE"}) unless env["REQUEST_METHOD"] == "GET"
        body = CodeLobbySuiteReport.redis.get(CLEANUP_PREFIX + token)
        return response(200, {state: "PENDING"}) unless body
        return response(503, {state: "UNAVAILABLE"}) if body.bytesize > 128
        return [200, {"Content-Type" => "application/json", "Cache-Control" => "no-store"}, [body]]
      end
      redis = CodeLobbySuiteReport.redis
      case env["REQUEST_METHOD"]
      when "GET"
        body = redis.get(PREFIX + token)
        return response(200, {state: "MISSING"}) unless body
        return response(503, {state: "UNAVAILABLE"}) if body.bytesize > 2_000_512
        [200, {"Content-Type" => "application/json", "Cache-Control" => "no-store"}, [body]]
      when "DELETE"
        redis.multi do |transaction|
          transaction.del(PREFIX + token)
          transaction.zrem(PREFIX + "index", PREFIX + token)
          transaction.del(CLEANUP_PREFIX + token)
          transaction.zrem(CLEANUP_PREFIX + "index", CLEANUP_PREFIX + token)
        end
        response(200, {state: "DELETED"})
      else
        response(405, {state: "UNAVAILABLE"})
      end
    rescue Redis::BaseError
      response(503, {state: "UNAVAILABLE"})
    end

    private

    def response(status, value)
      [status, {"Content-Type" => "application/json", "Cache-Control" => "no-store"}, [JSON.generate(value)]]
    end
  end
end

Rails.application.config.middleware.use(CodeLobbyJavaArtifactEndpoint::Endpoint)
