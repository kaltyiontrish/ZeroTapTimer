# Converts logo/Logo.svg into the adaptive launcher foreground.
#
# Android cannot read an SVG, and the path data is ~7kB, so the conversion is done here
# rather than by hand: every coordinate is scaled and re-emitted numerically, which
# removes any chance of a mistyped digit silently changing the shape.
#
# Run from the repo root:  powershell -File logo/convert-icon.ps1

$svgPath = Join-Path $PSScriptRoot 'Logo.svg'
$outPath = Join-Path $PSScriptRoot '..\app\src\main\res\drawable\ic_launcher_foreground.xml'
$inv = [System.Globalization.CultureInfo]::InvariantCulture

$svg = Get-Content -Raw -LiteralPath $svgPath
$paths = [regex]::Matches($svg, '<path\s+d="([^"]+)"')
if ($paths.Count -eq 0) { throw "no <path> found in $svgPath" }

# The last path is the glyph on its own. The first one is the background square with the
# same glyph baked in as a subpath, which is not what an adaptive foreground wants.
$glyph = $paths[$paths.Count - 1].Groups[1].Value

# Absolute commands only: relative ones are deltas, and scaling them would distort the
# shape. -cmatch, not -match, because PowerShell's -match is case-insensitive and would
# flag the uppercase 'C' as a relative 'c'.
$letters = (([regex]::Matches($glyph, '[A-Za-z]') | ForEach-Object { $_.Value }) |
    Sort-Object -Unique) -join ''
if ($letters -cmatch '[a-z]') { throw "relative path commands found ($letters)" }
Write-Host "glyph uses commands: $letters"

# Walk the path command by command. A naive min/max over the whole number stream mixes
# the two axes, because x and y alternate and neither is self-describing.
$cmds = [regex]::Matches($glyph, '([A-Z])([^A-Z]*)')
$xs = New-Object System.Collections.ArrayList
$ys = New-Object System.Collections.ArrayList
foreach ($m in $cmds) {
    $vals = @([regex]::Matches($m.Groups[2].Value, '-?\d+(?:\.\d+)?') |
        ForEach-Object { [double]$_.Value })
    if ($vals.Count % 2 -ne 0) {
        throw "command $($m.Groups[1].Value) has an odd number of coordinates"
    }
    for ($i = 0; $i -lt $vals.Count; $i += 2) {
        [void]$xs.Add($vals[$i])
        [void]$ys.Add($vals[$i + 1])
    }
}
$minX = ($xs | Measure-Object -Minimum).Minimum
$maxX = ($xs | Measure-Object -Maximum).Maximum
$minY = ($ys | Measure-Object -Minimum).Minimum
$maxY = ($ys | Measure-Object -Maximum).Maximum
Write-Host ("extents: x {0}..{1}  y {2}..{3}" -f $minX, $maxX, $minY, $maxY)

# Adaptive icons mask the outer 18dp of a 108dp canvas, so the glyph has to sit well
# inside 30..78. 50 units leaves a little more room than the safe zone strictly needs.
# The box above is the control-point extent, which is slightly larger than the drawn
# curve, so the glyph lands a touch smaller than 50 rather than clipping.
$target = 50.0
$centre = 54.0
$scale = $target / [Math]::Max($maxX - $minX, $maxY - $minY)
$offX = $centre - (($minX + $maxX) / 2) * $scale
$offY = $centre - (($minY + $maxY) / 2) * $scale
Write-Host ("scale {0}  offset {1},{2}" -f $scale, $offX, $offY)

$sb = New-Object System.Text.StringBuilder
$pos = 0
foreach ($m in $cmds) {
    [void]$sb.Append($glyph.Substring($pos, $m.Index - $pos))
    [void]$sb.Append($m.Groups[1].Value)
    $vals = @([regex]::Matches($m.Groups[2].Value, '-?\d+(?:\.\d+)?') |
        ForEach-Object { [double]$_.Value })
    for ($i = 0; $i -lt $vals.Count; $i += 2) {
        if ($i -gt 0) { [void]$sb.Append(' ') }
        [void]$sb.Append(($vals[$i] * $scale + $offX).ToString('0.###', $inv))
        [void]$sb.Append(' ')
        [void]$sb.Append(($vals[$i + 1] * $scale + $offY).ToString('0.###', $inv))
    }
    $pos = $m.Index + $m.Length
}
[void]$sb.Append($glyph.Substring($pos))
$out = $sb.ToString()

$xml = @"
<?xml version="1.0" encoding="utf-8"?>
<!-- GENERATED from logo/Logo.svg by logo/convert-icon.ps1. Do not hand-edit. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">

    <path
        android:fillColor="#FFFFFF"
        android:fillType="nonZero"
        android:pathData="$out" />
</vector>
"@

[System.IO.File]::WriteAllText($outPath, $xml, (New-Object System.Text.UTF8Encoding($false)))
Write-Host "wrote $outPath ($($xml.Length) chars)"

