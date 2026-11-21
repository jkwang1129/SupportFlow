$ErrorActionPreference = 'Stop'
$fixtureUrl = 'http://127.0.0.1:17364/__approval-fixture'
$browserSession = 'sf-approval-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)

function Browser {
    & agent-browser --session $browserSession @args
    if ($LASTEXITCODE -ne 0) { throw "Browser check failed: $($args -join ' ')" }
}

function Open-Workspace([string]$url) {
    Browser open $url
    Browser wait --text '测试客户 A' | Out-Null
    Browser wait --text '本地验收' | Out-Null
}

function Open-Approval([string]$customer) {
    Browser find role button click --name $customer | Out-Null
    Browser find role button click --name '工单审批' --exact | Out-Null
}

function Close-Approval {
    Browser find role button click --name '关闭' --exact | Out-Null
    Browser wait --fn "!Array.from(document.querySelectorAll('.el-overlay-dialog')).some(element => element.getClientRects().length)" | Out-Null
}

try {
    Write-Output "Checking workspace at $fixtureUrl in $browserSession"
    Open-Workspace $fixtureUrl
    Open-Approval '测试客户 A'
    Browser wait --text '合成工单提案' | Out-Null
    Browser wait --text '<fixture-text>原文以纯文本显示</fixture-text>' | Out-Null
    if ((Browser get count 'fixture-text') -ne '0') { throw 'Approval content was interpreted as markup' }
    Browser find role button click --name '批准' --exact | Out-Null
    Browser wait --text '已批准，执行前可撤销' | Out-Null
    Browser find role button click --name '撤销批准' --exact | Out-Null
    Browser wait --text '当前会话没有可审查的工单提案。' | Out-Null
    Close-Approval
    Write-Output 'PASS original text, approval, and revocation'

    Open-Approval '测试客户 B'
    Browser wait --text '当前会话没有可审查的工单提案。' | Out-Null
    Close-Approval
    Write-Output 'PASS no active proposal'

    Open-Workspace $fixtureUrl
    Open-Approval '测试客户 C'
    Browser wait --text '合成工单提案' | Out-Null
    Browser find role button click --name '批准' --exact | Out-Null
    Browser wait --text '审批状态已变化或过期，请刷新。' | Out-Null
    if ((Browser snapshot -s '.el-dialog' -i) -match 'button "批准"') { throw 'A stale proposal can still be submitted' }
    Close-Approval
    Write-Output 'PASS conflict clears obsolete actions'

    Open-Workspace $fixtureUrl
    Open-Approval '测试客户 A'
    Browser wait --text '合成工单提案' | Out-Null
    Browser find role button click --name '拒绝' --exact | Out-Null
    Browser wait --text '当前会话没有可审查的工单提案。' | Out-Null
    Write-Output 'PASS denial'

    Open-Workspace "$($fixtureUrl)?role=agent"
    Browser find role button click --name '测试客户 A' | Out-Null
    Browser wait --text '会话 FIXTURE_APPROVAL' | Out-Null
    if ((Browser snapshot -i) -match '工单审批') { throw 'Ordinary staff can see the approval action' }
    Write-Output 'PASS ordinary staff cannot see the approval action'
} finally {
    & agent-browser --session $browserSession close | Out-Null
}
