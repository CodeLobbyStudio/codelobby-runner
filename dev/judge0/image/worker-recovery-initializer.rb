require "digest"
require "/usr/local/lib/codelobby/worker-recovery"

Rails.application.config.to_prepare do
  source = Rails.root.join("app/jobs/isolate_job.rb")
  expected = "61e8aab9d40ada8caf2df9a2e381395ff04cd1415a3c6b3d0d7638178d9c29a6"
  raise "Review worker recovery for this Judge0 version" unless Digest::SHA256.file(source).hexdigest == expected
  IsolateJob.prepend(CodeLobbyWorkerRecovery::Job) unless IsolateJob.ancestors.include?(CodeLobbyWorkerRecovery::Job)
end

Rails.application.config.after_initialize do
  # Only worker boot may recover. No live-request or periodic recovery is permitted.
  if ENV["CODELOBBY_WORKER_RECOVERY"] == "1"
    CodeLobbyWorkerRecovery.recover(
      store: CodeLobbyWorkerRecovery::Store.new,
      pool: CodeLobbySandboxSlots::Pool.new,
      find: lambda do |record|
        submission = Submission.find(record.fetch("id"))
        raise "Recovery submission identity mismatch" unless submission.token == record.fetch("token")
        submission
      end,
      cleanup: lambda do |id|
        ok = system("/usr/bin/sudo", "/bin/bash", "/usr/local/bin/workspace-helper.sh", "recover", id.to_s,
                    out: File::NULL, err: File::NULL)
        raise "Worker recovery cleanup unconfirmed" unless ok
      end,
      finish: lambda do |submission|
        expected_token = submission.token
        submission.with_lock do
          raise "Recovery submission identity changed" unless submission.token == expected_token
          submission.update!(status: Status.boxerr, message: CodeLobbyWorkerRecovery::MESSAGE,
                             finished_at: DateTime.now, stdout: nil, stderr: nil, compile_output: nil,
                             time: nil, wall_time: nil, memory: nil, exit_code: nil, exit_signal: nil)
        end
      end
    )
  end
end
