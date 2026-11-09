# Runs outside Isolate. Pin each directory before opening the next component.
id = ARGV.fetch(0)
exit 1 unless /\A(?:0|[1-9][0-9]{0,2})\z/.match?(id)

limit = 1_000_000
flags = File::RDONLY | File::NOFOLLOW | File::NONBLOCK
begin
  File.open("/var/local/lib/isolate/#{id}/box", flags) do |box|
    exit 2 unless box.stat.directory?
    File.open("/proc/self/fd/#{box.fileno}/.codelobby", flags) do |directory|
      exit 2 unless directory.stat.directory?
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
