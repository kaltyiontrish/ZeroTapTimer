# Verifies the test sources are structurally sound. Brace balance and @Test/function
# counts both pass on files that still will not compile, which is how a duplicate @Test
# survived twice: it balances and it counts. This checks the thing that actually matters.
#
#   powershell -File check-tests.ps1

$root = $PSScriptRoot
$testRoot = Join-Path $root 'app\src\test'
if (-not (Test-Path -LiteralPath $testRoot)) {
    throw "test sources not found at $testRoot"
}
$problems = New-Object System.Collections.ArrayList

foreach ($file in Get-ChildItem -Recurse -Filter *.kt -Path $testRoot) {
    $lines = Get-Content -LiteralPath $file.FullName

    # 1. Every @Test must be attached to a fun, skipping blanks and doc comments.
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i].Trim() -ne '@Test') { continue }
        $j = $i + 1
        while ($j -lt $lines.Count) {
            $t = $lines[$j].Trim()
            if ($t -ne '' -and -not $t.StartsWith('//') -and -not $t.StartsWith('*')) { break }
            $j++
        }
        if ($j -ge $lines.Count -or -not $lines[$j].Trim().StartsWith('fun ')) {
            [void]$problems.Add("$($file.Name):$($i + 1) @Test is not attached to a function")
        }
    }

    # 2. Two @Test in a row anywhere: JUnit 4's is not @Repeatable.
    for ($i = 0; $i -lt $lines.Count - 1; $i++) {
        if ($lines[$i].Trim() -eq '@Test' -and $lines[$i + 1].Trim() -eq '@Test') {
            [void]$problems.Add("$($file.Name):$($i + 1) two @Test annotations stacked")
        }
    }

    # 3. kotlin.math.abs has no Short overload, so a Short argument must be widened
    #    before the call. Nested parentheses defeat a tidy regex, so this looks for any
    #    conversion after the call rather than trying to match the argument.
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match 'abs\(' -and $lines[$i] -notmatch 'abs\(.*\.to') {
            [void]$problems.Add("$($file.Name):$($i + 1) abs() with no widening: $($lines[$i].Trim())")
        }
    }

    # 4. Every backticked test function needs its @Test, or it silently never runs.
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i].Trim() -notmatch '^fun `') { continue }
        $j = $i - 1
        while ($j -ge 0 -and ($lines[$j].Trim() -eq '' -or $lines[$j].Trim().StartsWith('//'))) { $j-- }
        if ($j -lt 0 -or $lines[$j].Trim() -notmatch '^@Test\b') {
            [void]$problems.Add("$($file.Name):$($i + 1) test function has no @Test: $($lines[$i].Trim())")
        }
    }

    # 5. Parens balance, which catches the truncated edit that produced '    }        }'.
    $text = ($lines -join "`n")
    foreach ($pair in @(@('{', '}'), @('(', ')'))) {
        $open = ([regex]::Matches($text, [regex]::Escape($pair[0]))).Count
        $close = ([regex]::Matches($text, [regex]::Escape($pair[1]))).Count
        if ($open -ne $close) {
            [void]$problems.Add("$($file.Name) $($pair[0])=$open $($pair[1])=$close")
        }
    }

    # 6. Counts, as a cross-check on the two structural checks above.
    $fns = ([regex]::Matches($text, 'fun `')).Count
    $tests = ([regex]::Matches($text, '@Test\b')).Count
    if ($tests -ne $fns) {
        [void]$problems.Add("$($file.Name) @Test=$tests but fun=$fns")
    }
}

if ($problems.Count -eq 0) {
    $count = (Get-ChildItem -Recurse -Filter *.kt -Path $testRoot | Measure-Object).Count
    Write-Output "test sources OK ($count files checked)"
} else {
    $problems | ForEach-Object { Write-Output $_ }
    Write-Output "($($problems.Count) problem(s))"
    exit 1
}
