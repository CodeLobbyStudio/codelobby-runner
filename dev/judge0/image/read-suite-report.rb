# Runs outside Isolate. Pin each directory before opening the next component.
id = ARGV.fetch(0)
exit 1 unless /\A(?:0|[1-9][0-9]{0,2})\z/.match?(id)

mode = ARGV.fetch(1, "report")
exit 1 unless ["report", "compile-budget", "configuration-error", "java-artifacts"].include?(mode)
limit = 1_000_000
flags = File::RDONLY | File::NOFOLLOW | File::NONBLOCK
begin
  File.open("/var/local/lib/isolate/#{id}/box", flags) do |box|
    exit 2 unless box.stat.directory?
    if mode == "java-artifacts"
      require "/usr/local/lib/codelobby/java-artifacts"
      root = "/sys/fs/cgroup/codelobby-runner-judge0-v1"
      exit 1 unless File.read("/run/isolate/cgroup", 256).strip == root
      File.open("#{root}/box-#{id}", flags) do |group|
        exit 1 unless group.stat.directory? && group.stat.uid == 0
        File.open("/proc/self/fd/#{group.fileno}/cgroup.events", flags) do |events|
          exit 1 unless (events.read(4096) || "").lines.any? { |line| line.strip == "populated 0" }
        end
      end
      begin
        STDOUT.binmode
        STDOUT.write(CodeLobbyJavaArtifacts.capture(box))
        exit 0
      rescue CodeLobbyJavaArtifacts::TooLarge
        exit 3
      rescue CodeLobbyJavaArtifacts::Rejected
        exit 2
      end
    end
    if mode == "compile-budget"
      path = "/proc/self/fd/#{box.fileno}/compile"
      exit 2 unless File.lstat(path).file?
      File.open(path, flags) do |script|
        exit 1 unless script.stat.file? && script.stat.nlink == 1
        header = (script.read(4096) || "").split("\n").fetch(1, "")
        exit 2 unless header.start_with?("# CODELOBBY_BUILD_LIMITS")
        match = /\A# CODELOBBY_BUILD_LIMITS ([1-9][0-9]{0,4}) ([1-9][0-9]{0,4})\z/.match(header)
        exit 1 unless match
        cpu, wall = match.captures.map { |value| Integer(value, 10) }
        exit 1 unless (1..10_000).cover?(cpu) && (1..20_000).cover?(wall)
        STDOUT.write("#{cpu} #{wall}")
      end
      exit 0
    end
    File.open("/proc/self/fd/#{box.fileno}/.codelobby", flags) do |directory|
      exit 2 unless directory.stat.directory?
      if mode == "configuration-error"
        path = "/proc/self/fd/#{directory.fileno}/configuration-error"
        exit 2 unless File.lstat(path).file?
        File.open(path, flags) do |marker|
          exit 2 unless marker.stat.file? && marker.stat.nlink == 1
          exit(marker.read(32) == "INVALID_HOST_TEST_CONFIGURATION" ? 0 : 2)
        end
      end
      path = "/proc/self/fd/#{directory.fileno}/report.ndjson"
      exit 2 unless File.lstat(path).file?
      File.open(path, flags) do |report|
        stat = report.stat
        exit 2 unless stat.file? && stat.nlink == 1
        exit 3 if stat.size > limit
        bytes = report.read(limit + 1) || "".b
        exit 3 if bytes.bytesize > limit
        exit 2 if bytes.empty?
        STDOUT.binmode
        STDOUT.write(bytes)
      end
    end
  end
rescue Errno::ENOENT, Errno::ENOTDIR, Errno::ELOOP, Errno::EACCES
  exit 2
end
