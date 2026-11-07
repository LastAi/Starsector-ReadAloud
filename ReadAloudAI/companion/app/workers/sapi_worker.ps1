# Windows built-in voice (SAPI) worker. Same JSON-lines protocol as the Python
# workers (see PROTOCOL.md). Input JSON is ASCII-only (non-ASCII is \u-escaped by
# the server), so console code pages can't garble the text.
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8

function Send($obj) {
    [Console]::Out.WriteLine(($obj | ConvertTo-Json -Compress -Depth 4))
    [Console]::Out.Flush()
}

try {
    Add-Type -AssemblyName System.Speech
    $synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
} catch {
    Send @{ event = 'error'; id = $null; message = "System.Speech is not available: $($_.Exception.Message)" }
    exit 1
}
$infos = @($synth.GetInstalledVoices() | Where-Object { $_.Enabled } | ForEach-Object { $_.VoiceInfo })
$voices = @($infos | ForEach-Object { $_.Name })
# Voices added by "natural voice" adapters (names ending in "Online") stream to the
# speakers only: saved to a file they produce silence, after a ~5 s network wait.
# Keep an installed voice to fall back on, English first (game text is English).
$offlineInfos = @($infos | Where-Object { $_.Name -notmatch 'Online$' })
$offline = @($offlineInfos | ForEach-Object { $_.Name })
$english = @($offlineInfos | Where-Object { $_.Culture.TwoLetterISOLanguageName -eq 'en' } | ForEach-Object { $_.Name })
$fallbackVoice = if ($english.Count -gt 0) { $english[0] } elseif ($offline.Count -gt 0) { $offline[0] } else { $synth.Voice.Name }
$defaultVoice = $synth.Voice.Name
if ($defaultVoice -match 'Online$') { $defaultVoice = $fallbackVoice }

function SpeakTo($name, $text, $path) {
    $synth.SelectVoice($name)
    $synth.SetOutputToWaveFile($path)
    try { $synth.Speak($text) } finally { $synth.SetOutputToNull() }
    return (Get-Item -LiteralPath $path).Length -gt 1000
}

while ($true) {
    $line = [Console]::In.ReadLine()
    if ($null -eq $line) { break }
    if ($line.Trim().Length -eq 0) { continue }
    $req = $line | ConvertFrom-Json
    try {
        switch ($req.op) {
            'load' {
                $rate = 0
                if ($null -ne $req.settings -and $null -ne $req.settings.rate) { $rate = [int]$req.settings.rate }
                $synth.Rate = [Math]::Max(-10, [Math]::Min(10, $rate))
                Send @{ event = 'loaded'; voices = $offline; info = @{ device = 'Windows SAPI'; default = $defaultVoice } }
            }
            'synth' {
                $name = $defaultVoice
                if ($req.voice -is [string] -and $req.voice.Length -gt 0 -and ($voices -contains $req.voice)) { $name = $req.voice }
                $ok = SpeakTo $name ([string]$req.text) $req.out
                if (-not $ok -and $name -ne $fallbackVoice) {
                    Send @{ event = 'log'; message = "Voice '$name' produced no audio when saved to a file (online voices only play live); using '$fallbackVoice'." }
                    $ok = SpeakTo $fallbackVoice ([string]$req.text) $req.out
                }
                Send @{ event = 'done'; id = $req.id }
            }
            'quit' { exit 0 }
        }
    } catch {
        $rid = $null
        if ($req.op -eq 'synth') { $rid = $req.id }
        Send @{ event = 'error'; id = $rid; message = $_.Exception.Message }
    }
}
