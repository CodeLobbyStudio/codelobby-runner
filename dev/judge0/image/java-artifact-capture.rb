# Experimental only. This initializer is not loaded by the default Compose files.
require "base64"
require "digest"
require "json"
require "open3"

module CodeLobbyJavaArtifactCapture
  PREFIX = "codelobby:java-artifacts:prototype:v1:"
  CLEANUP_PREFIX = PREFIX + "cleanup:"
  TOKEN = /\A[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\z/
  STORE = <<~LUA
    local expired = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1] - 300)
    for _, key in ipairs(expired) do redis.call('DEL', key) end
    redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1] - 300)
    redis.call('SET', KEYS[2], ARGV[2], 'EX', 300)
    redis.call('ZADD', KEYS[1], ARGV[1], KEYS[2])
    local overflow = redis.call('ZCARD', KEYS[1]) - 32
    if overflow > 0 then
      local evicted = redis.call('ZRANGE', KEYS[1], 0, overflow - 1)
      for _, key in ipairs(evicted) do redis.call('DEL', key) end
      redis.call('ZREMRANGEBYRANK', KEYS[1], 0, overflow - 1)
    end
    redis.call('EXPIRE', KEYS[1], 300)
  LUA

  def self.redis
    CodeLobbySuiteReport.redis
  end

  private

  def compile
    outcome = super
    if outcome == :success && ENV["CODELOBBY_CAPTURE_JAVA_ARTIFACTS"] == "true" && submission.is_project && @codelobby_compilation_started == true
      capture_java_artifacts
    end
    outcome
  end

  def cleanup(*args)
    held_lease = !@codelobby_sandbox_lease.nil?
    outcome = super
    if held_lease && @codelobby_sandbox_lease.nil? && !@codelobby_sandbox_cleanup_failed &&
        ENV["CODELOBBY_CAPTURE_JAVA_ARTIFACTS"] == "true" && submission.is_project && TOKEN.match?(submission.token)
      begin
        CodeLobbyJavaArtifactCapture.redis.eval(STORE,
          keys: [CLEANUP_PREFIX + "index", CLEANUP_PREFIX + submission.token],
          argv: [Time.now.to_f, JSON.generate(state: "CLEANED_UP")])
      rescue StandardError
        Rails.logger.warn("Execution cleanup confirmation unavailable")
      end
    end
    outcome
  end

  def capture_java_artifacts
    return unless TOKEN.match?(submission.token)
    bytes, result = Open3.capture2("/usr/bin/sudo", "-n", "/usr/local/ruby-2.7.0/bin/ruby",
                                  "/usr/local/bin/read-suite-report.rb", Integer(box_id).to_s,
                                  "java-artifacts", binmode: true, err: File::NULL)
    state = case result.exitstatus
            when 0 then "AVAILABLE"
            when 2 then "MISSING"
            when 3 then "TOO_LARGE"
            else "UNAVAILABLE"
            end
    value = {state: state}
    if state == "AVAILABLE"
      raise "Invalid artifact capture size" if bytes.empty? || bytes.bytesize > 1_500_000
      value[:snapshot] = Base64.strict_encode64(bytes)
      value[:sha256] = Digest::SHA256.hexdigest(bytes)
    end
    CodeLobbyJavaArtifactCapture.redis.eval(STORE,
      keys: [PREFIX + "index", PREFIX + submission.token], argv: [Time.now.to_f, JSON.generate(value)])
  rescue StandardError
    # Capturing is optional. Never log private paths, bytecode, raw helper output or exception text.
    Rails.logger.warn("Java artifact capture unavailable")
  end
end

Rails.application.config.to_prepare do
  source = Rails.root.join("app/jobs/isolate_job.rb")
  expected = "61e8aab9d40ada8caf2df9a2e381395ff04cd1415a3c6b3d0d7638178d9c29a6"
  raise "Review artifact capture for this Judge0 version" unless Digest::SHA256.file(source).hexdigest == expected
  IsolateJob.prepend(CodeLobbyJavaArtifactCapture) unless IsolateJob.ancestors.include?(CodeLobbyJavaArtifactCapture)
end
