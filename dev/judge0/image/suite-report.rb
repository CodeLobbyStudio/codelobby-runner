# Opt-in transport for the pinned local Judge0 image.
require "base64"
require "digest"
require "json"
require "open3"

module CodeLobbySuiteReport
  TOKEN = /\A[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\z/
  KEY_PREFIX = "codelobby:suite-report:v1:"
  INDEX = KEY_PREFIX + "index"
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
    @redis ||= Redis.new(host: ENV.fetch("REDIS_HOST"), port: ENV.fetch("REDIS_PORT"),
                        password: ENV.fetch("REDIS_PASSWORD"), timeout: 2)
  end

  class Endpoint
    def initialize(app)
      @app = app
    end

    def call(env)
      match = %r{\A/codelobby/v1/suite-reports/([^/]+)\z}.match(env["PATH_INFO"])
      return @app.call(env) unless env["REQUEST_METHOD"] == "GET" && match && TOKEN.match?(match[1])

      body = CodeLobbySuiteReport.redis.get(KEY_PREFIX + match[1]) || '{"state":"MISSING"}'
      [200, {"Content-Type" => "application/json", "Cache-Control" => "no-store"}, [body]]
    rescue Redis::BaseError
      [503, {"Content-Type" => "application/json", "Cache-Control" => "no-store"}, ['{"error":"Unavailable"}']]
    end
  end

  private

  def compile
    @codelobby_build_limits = nil
    @codelobby_build_usage = nil
    if submission.is_project
      header, result = Open3.capture2("/usr/bin/sudo", "-n", "/usr/local/ruby-2.7.0/bin/ruby",
                                     "/usr/local/bin/read-suite-report.rb", Integer(box_id).to_s, "compile-budget")
      raise "Invalid project compilation budget" unless [0, 2].include?(result.exitstatus)
      @codelobby_build_limits = header.split.map { |value| Integer(value, 10) } if result.success?
    end
    outcome = super
    store_suite_report(state: "MISSING") if outcome == :failure
    outcome
  end

  def get_metadata
    metadata = super
    if @codelobby_compiling && metadata[:time] && metadata[:"time-wall"]
      cpu = Float(metadata[:time]) * 1000
      wall = Float(metadata[:"time-wall"]) * 1000
      if cpu.finite? && wall.finite? && cpu >= 0 && wall >= 0
        @codelobby_build_usage = {cpuTimeMs: cpu, wallTimeMs: wall}
        # Compile failures become terminal before compile returns. Publish usage before that save.
        store_suite_report(state: "MISSING")
      end
    end
    metadata
  end

  def verify
    super
    return unless submission.is_project && submission.number_of_runs == 1 && TOKEN.match?(submission.token)

    report, result = Open3.capture2("/usr/bin/sudo", "-n", "/usr/local/ruby-2.7.0/bin/ruby",
                                   "/usr/local/bin/read-suite-report.rb", Integer(box_id).to_s, binmode: true)
    value = case result.exitstatus
            when 0 then {state: "AVAILABLE", report: Base64.strict_encode64(report)}
            when 2 then {state: "MISSING"}
            when 3 then {state: "TOO_LARGE"}
            else return
            end
    store_suite_report(value)
  end

  def store_suite_report(value)
    return unless submission.is_project && TOKEN.match?(submission.token)
    value[:compilation] = @codelobby_build_usage if @codelobby_build_usage
    CodeLobbySuiteReport.redis.eval(STORE, keys: [INDEX, KEY_PREFIX + submission.token],
                                   argv: [Time.now.to_f, JSON.generate(value)])
  rescue Redis::BaseError, SystemCallError
    Rails.logger.warn("Suite report capture unavailable")
  end
end

Rails.application.config.middleware.use(CodeLobbySuiteReport::Endpoint)
Rails.application.config.to_prepare do
  source = Rails.root.join("app/jobs/isolate_job.rb")
  expected = "61e8aab9d40ada8caf2df9a2e381395ff04cd1415a3c6b3d0d7638178d9c29a6"
  raise "Review suite report transport for this Judge0 version" unless Digest::SHA256.file(source).hexdigest == expected
  IsolateJob.prepend(CodeLobbySuiteReport) unless IsolateJob.ancestors.include?(CodeLobbySuiteReport)
end
