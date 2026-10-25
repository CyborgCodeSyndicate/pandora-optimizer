using System.Text.Json;
using Pandora.Optimizer.DotNet;
if (args.Contains("--describe"))
{
    Console.WriteLine("{\"protocolVersion\":1,\"language\":\"csharp\",\"features\":[\"source\",\"lessons\",\"compass\",\"parameters\",\"enum-options\"]}");
    return;
}
while (await Console.In.ReadLineAsync() is { } line)
{
    try
    {
        if (line.Length > 20 * 1024 * 1024) throw new InvalidDataException("Request exceeds size limit");
        var request = JsonSerializer.Deserialize<ScanRequest>(line, Protocol.Json) ?? throw new InvalidDataException("Missing request");
        if (request.ProtocolVersion != 1) throw new InvalidDataException("Unsupported protocol version");
        Console.WriteLine(JsonSerializer.Serialize(new CSharpAdapter().Scan(request), Protocol.Json));
    }
    catch (Exception)
    {
        Console.Error.WriteLine("C# adapter failed. Check source roots, references and protocol version.");
        Environment.ExitCode = 2; break;
    }
}
