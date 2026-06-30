$ErrorActionPreference = "Stop"
# Get git credential
$input = @"
protocol=https
host=github.com

"@
$output = $input | git credential fill 2>$null
$username = ($output | Select-String "username=(.*)" | % { $_.Matches.Groups[1].Value })
$password = ($output | Select-String "password=(.*)" | % { $_.Matches.Groups[1].Value })

if (-not $password) {
    Write-Error "Cannot get GitHub token from credential store"
    exit 1
}

Write-Output "Got credentials for: $username"
Write-Output "Token length: $($password.Length)"

# Step 1: Delete the repository
Write-Output "Deleting repository lkiq/Test..."
$deleteUrl = "https://api.github.com/repos/lkiq/Test"
try {
    $deleteResult = Invoke-RestMethod -Uri $deleteUrl -Method Delete -Headers @{
        Authorization = "Basic " + [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("${username}:${password}"))
        Accept = "application/vnd.github+json"
    }
    Write-Output "Repository deleted successfully."
} catch {
    Write-Output "Delete response: $($_.Exception.Response.StatusCode.value__) $($_.Exception.Message)"
    if ($_.Exception.Response.StatusCode.value__ -eq 404) {
        Write-Output "Repository already deleted or does not exist."
    } elseif ($_.Exception.Response.StatusCode.value__ -eq 403) {
        Write-Output "Permission denied. Your token may not have delete_repo scope."
        Write-Output "Requesting user to delete manually via GitHub settings page."
        Write-Output "URL: https://github.com/lkiq/Test/settings"
        exit 1
    } else {
        Write-Error "Failed to delete repository"
        exit 1
    }
}

# Step 2: Create a new empty repository
Write-Output "Creating new empty repository lkiq/Test..."
$createUrl = "https://api.github.com/repos/lkiq/Test/generate"
# Actually use the create repo API
$createUrl = "https://api.github.com/user/repos"
$body = @{
    name = "Test"
    private = $false
    auto_init = $false
    description = "AI智能求职辅导平台"
} | ConvertTo-Json

try {
    $createResult = Invoke-RestMethod -Uri $createUrl -Method Post -Headers @{
        Authorization = "Basic " + [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("${username}:${password}"))
        Accept = "application/vnd.github+json"
    } -Body $body -ContentType "application/json"
    Write-Output "Repository created: $($createResult.html_url)"
} catch {
    Write-Output "Create response: $($_.Exception.Response.StatusCode.value__) $($_.Exception.Message)"
    if ($_.Exception.Response.StatusCode.value__ -eq 422) {
        Write-Output "Repository already exists (may need to delete first)."
    }
    Write-Error "Failed to create repository"
    exit 1
}

Write-Output "Done! Repository is ready."
