import { expect, test, type Page, type Route } from '@playwright/test'
import type { ApprovalProgress } from '../src/api/customer/agentApproval'

const approvalPath = '/api/customer/agent-approvals'
const firstSession = { sessionNo: 'SESSION_ALPHA', customerName: '测试访客甲', status: 2 }
const secondSession = { sessionNo: 'SESSION_BETA', customerName: '测试访客乙', status: 2 }
const reference = {
  source_event_id: '20000000-0000-4000-8000-000000000001',
  run_id: '20000000-0000-4000-8000-000000000002',
  expires_at: '2030-01-01T00:00:00Z',
}
const proposal = {
  approval_id: '20000000-0000-4000-8000-000000000003',
  arguments_sha256: 'a'.repeat(64),
  title: '测试工单标题',
  content: '测试工单正文',
  expires_at: reference.expires_at,
}
const eventsPath = `${approvalPath}/events/${reference.source_event_id}/runs/${reference.run_id}`

function reply(route: Route, data: unknown) {
  return route.fulfill({ json: { code: 0, data } })
}

async function workspace(page: Page, role = 'ADMIN') {
  const fixture = {
    active: 'PENDING' as 'PENDING' | 'APPROVED' | null,
    previewStatus: 200,
    progressStatus: 200,
    progress: { state: 'PENDING', attempt: 1, step_count: 4 } as ApprovalProgress,
    progressUnavailable: false,
    progressReads: 0,
    latest: null as ApprovalProgress | null,
    latestStatus: 200,
    latestSessions: [] as string[],
    activeSessions: [] as string[],
    decisions: [] as unknown[],
  }
  await page.addInitScript(() => {
    localStorage.setItem('supportflow_admin_token', 'test-workspace-user')
    localStorage.setItem('supportflow_admin_tenant', 'TEST_APPROVAL')
    localStorage.setItem('supportflow_onboard_done', 'true')
  })
  await page.routeWebSocket('**/ws/realtime?*', () => {})
  await page.route('/api/**', async (route) => {
    const path = new URL(route.request().url()).pathname
    if (path.startsWith(`${approvalPath}/sessions/`) && path.endsWith('/active')) {
      const sessionNo = path.split('/').at(-2)!
      fixture.activeSessions.push(sessionNo)
      return reply(route, sessionNo === firstSession.sessionNo && fixture.active
        ? { ...reference, status: fixture.active } : null)
    }
    if (path.startsWith(`${approvalPath}/sessions/`) && path.endsWith('/latest-progress')) {
      const sessionNo = path.split('/').at(-2)!
      fixture.latestSessions.push(sessionNo)
      if (fixture.latestStatus !== 200) return route.fulfill({ status: fixture.latestStatus, json: {} })
      return reply(route, sessionNo === firstSession.sessionNo ? fixture.latest : null)
    }
    if (path === `${eventsPath}/proposal`) {
      if (fixture.previewStatus !== 200) return route.fulfill({ status: fixture.previewStatus, json: {} })
      return reply(route, { ...proposal, status: fixture.active })
    }
    if (path === `${eventsPath}/progress`) {
      fixture.progressReads++
      if (fixture.progressUnavailable) return route.abort('internetdisconnected')
      if (fixture.progressStatus !== 200) return route.fulfill({ status: fixture.progressStatus, json: {} })
      return reply(route, fixture.progress)
    }
    if (path === `${eventsPath}/decisions` && route.request().method() === 'POST') {
      fixture.decisions.push(route.request().postDataJSON())
      fixture.active = 'APPROVED'
      return reply(route, { approval_id: proposal.approval_id, status: 'APPROVED' })
    }
    switch (path) {
      case '/api/user/auth/me':
        return reply(route, { userId: 42, userNo: 'E42', name: '测试坐席', userType: 2, tenantCode: 'TEST_APPROVAL', roles: [role] })
      case '/api/user/members/access':
        return reply(route, { canManage: role === 'ADMIN', roleName: '测试坐席', roleCode: role })
      case '/api/tenant/enterprise/my':
        return reply(route, { stage: 'APPROVED' })
      case '/api/customer/sessions':
        return reply(route, [firstSession, secondSession])
      case `/api/customer/sessions/${firstSession.sessionNo}`:
        return reply(route, firstSession)
      case `/api/customer/sessions/${secondSession.sessionNo}`:
        return reply(route, secondSession)
      case '/api/customer/sessions/summary':
        return reply(route, { mine: 0, queue: 2 })
      case '/api/customer/agent-status':
        return reply(route, { mine: { status: 1 }, agents: [] })
      case '/api/realtime/presence':
        return reply(route, { onlineSessions: [] })
    }
    if (path.startsWith('/api/customer/agent-approvals/')) {
      throw new Error(`Unexpected approval request: ${path}`)
    }
    return reply(route, [])
  })
  return fixture
}

async function selectSession(page: Page, name = firstSession.customerName) {
  await page.getByRole('button', { name: new RegExp(name) }).click()
}

async function openReview(page: Page) {
  await page.getByRole('button', { name: '工单审批', exact: true }).click()
  return page.getByRole('dialog', { name: '工单审批' })
}

test('keeps the approved Run visible after refresh and reopening in the same session', async ({ page }) => {
  const fixture = await workspace(page)
  await page.goto('/modules/workspace')
  await selectSession(page)
  const dialog = await openReview(page)
  await expect(dialog.getByText(proposal.title)).toBeVisible()
  await expect(dialog.getByText(proposal.content)).toBeVisible()
  await dialog.getByRole('button', { name: '批准', exact: true }).click()
  await expect(dialog.getByRole('button', { name: '撤销批准' })).toBeVisible()
  expect(fixture.decisions).toEqual([{
    approval_id: proposal.approval_id, arguments_sha256: proposal.arguments_sha256, decision: 'approve',
  }])
  fixture.progress = { state: 'RUNNING', attempt: 1, step_count: 6 }
  await dialog.getByRole('button', { name: '查询执行进度' }).click()
  await expect(dialog.getByText('任务执行中')).toBeVisible()

  fixture.active = null
  fixture.progress = { state: 'SUCCEEDED', attempt: 1, step_count: 7 }
  await dialog.getByRole('button', { name: '刷新', exact: true }).click()
  await expect(dialog.getByText('任务执行完成')).toBeVisible()
  await expect(dialog.getByText('7', { exact: true })).toBeVisible()
  await expect(dialog.getByText(proposal.content)).toHaveCount(0)
  await expect(dialog.getByRole('button', { name: '撤销批准' })).toHaveCount(0)
  await dialog.getByRole('button', { name: '关闭', exact: true }).click()
  await expect(dialog).not.toBeVisible()
  await openReview(page)
  await expect(dialog.getByText('任务执行完成')).toBeVisible()
  expect(fixture.progressReads).toBe(3)
  expect(fixture.decisions).toHaveLength(1)

  await dialog.getByRole('button', { name: '关闭', exact: true }).click()
  await expect(dialog).not.toBeVisible()
  await selectSession(page, secondSession.customerName)
  await openReview(page)
  await expect(dialog.getByText('当前会话没有可审查的工单提案。')).toBeVisible()
  await expect(dialog.getByText('任务执行完成')).toHaveCount(0)
  expect(fixture.progressReads).toBe(3)
})

test('uses read-only progress when the approved proposal finishes before its preview returns', async ({ page }) => {
  const fixture = await workspace(page)
  fixture.active = 'APPROVED'
  fixture.previewStatus = 404
  fixture.progress = { state: 'SUCCEEDED', attempt: 1, step_count: 7 }
  await page.goto('/modules/workspace')
  await selectSession(page)
  const dialog = await openReview(page)
  await expect(dialog.getByText('任务执行完成')).toBeVisible()
  await expect(dialog.getByText(proposal.title)).toHaveCount(0)
  await expect(dialog.getByRole('button', { name: '撤销批准' })).toHaveCount(0)
  expect(fixture.decisions).toHaveLength(0)
})

for (const status of [403, 404]) {
  test(`forgets read-only progress after HTTP ${status}`, async ({ page }) => {
    const fixture = await workspace(page)
    fixture.active = 'APPROVED'
    fixture.previewStatus = 404
    fixture.progress = { state: 'FAILED', attempt: 2, step_count: 7 }
    await page.goto('/modules/workspace')
    await selectSession(page)
    const dialog = await openReview(page)
    await expect(dialog.getByText('任务执行失败')).toBeVisible()

    fixture.active = null
    fixture.progressStatus = status
    await dialog.getByRole('button', { name: '查询执行进度' }).click()
    await expect(dialog.getByText(status === 403 ? '当前账号没有工单审批权限。' : '会话或提案已不可审查。')).toBeVisible()
    await expect(dialog.getByText('任务执行失败')).toHaveCount(0)
    await expect(dialog.getByRole('button', { name: '查询执行进度' })).toHaveCount(0)
    await dialog.getByRole('button', { name: '刷新', exact: true }).click()
    await expect(dialog.getByText('当前会话没有可审查的工单提案。')).toBeVisible()
    expect(fixture.progressReads).toBe(2)
    expect(fixture.decisions).toHaveLength(0)
  })
}

test('retrying a disconnected progress read does not submit another decision', async ({ page }) => {
  const fixture = await workspace(page)
  await page.goto('/modules/workspace')
  await selectSession(page)
  const dialog = await openReview(page)
  await dialog.getByRole('button', { name: '批准', exact: true }).click()
  fixture.progressUnavailable = true
  await dialog.getByRole('button', { name: '查询执行进度' }).click()
  await expect(page.getByText('暂时无法获取执行进度，请稍后再查。')).toBeVisible()
  fixture.progressUnavailable = false
  fixture.progress = { state: 'RETRY_WAIT', attempt: 2, step_count: 7 }
  await dialog.getByRole('button', { name: '查询执行进度' }).click()
  await expect(dialog.getByText('任务等待重试')).toBeVisible()
  expect(fixture.progressReads).toBe(2)
  expect(fixture.decisions).toHaveLength(1)
})

test('late session detail cannot reopen approval for a previous selection', async ({ page }) => {
  const fixture = await workspace(page)
  let release!: () => void
  const pending = new Promise<void>((resolve) => { release = resolve })
  await page.route(`**/api/customer/sessions/${firstSession.sessionNo}`, async (route) => {
    await pending
    await reply(route, firstSession)
  })
  await page.goto('/modules/workspace')
  const firstDetail = page.waitForRequest(`**/api/customer/sessions/${firstSession.sessionNo}`)
  await selectSession(page)
  await firstDetail
  const secondDetail = page.waitForResponse(`**/api/customer/sessions/${secondSession.sessionNo}`)
  await selectSession(page, secondSession.customerName)
  await secondDetail
  const lateDetail = page.waitForResponse(`**/api/customer/sessions/${firstSession.sessionNo}`)
  release()
  await lateDetail
  const dialog = await openReview(page)
  await expect(dialog.getByText('当前会话没有可审查的工单提案。')).toBeVisible()
  expect(fixture.activeSessions).toEqual([secondSession.sessionNo])
})

test('does not expose approval to a regular agent', async ({ page }) => {
  const fixture = await workspace(page, 'AGENT')
  const profile = page.waitForResponse('**/api/user/auth/me')
  await page.goto('/modules/workspace')
  await profile
  await selectSession(page)
  await expect(page.getByText(/会话 SESSION_ALPHA/)).toBeVisible()
  await expect(page.getByRole('button', { name: '工单审批', exact: true })).toHaveCount(0)
  expect(fixture.activeSessions).toHaveLength(0)
  expect(fixture.latestSessions).toHaveLength(0)
})

test('after a full reload the reviewer finds terminal progress by the selected session', async ({ page }) => {
  const fixture = await workspace(page)
  await page.goto('/modules/workspace')
  await selectSession(page)
  const dialog = await openReview(page)
  await dialog.getByRole('button', { name: '批准', exact: true }).click()
  await expect(dialog.getByRole('button', { name: '撤销批准' })).toBeVisible()
  fixture.active = null
  fixture.latest = { state: 'SUCCEEDED', attempt: 2, step_count: 7 }

  await page.reload()
  await selectSession(page)
  await openReview(page)
  await expect(dialog.getByText('任务执行完成')).toBeVisible()
  await expect(dialog.getByText('7', { exact: true })).toBeVisible()
  await expect(dialog.getByText(proposal.content)).toHaveCount(0)
  await expect(dialog.getByRole('button', { name: '撤销批准' })).toHaveCount(0)
  expect(fixture.latestSessions).toEqual([firstSession.sessionNo])
  expect(fixture.progressReads).toBe(0)
  expect(fixture.decisions).toHaveLength(1)

  fixture.latestStatus = 404
  await dialog.getByRole('button', { name: '查询执行进度' }).click()
  await expect(dialog.getByText('会话或提案已不可审查。')).toBeVisible()
  await expect(dialog.getByText('任务执行完成')).toHaveCount(0)
})

test('an active proposal takes priority over reviewable terminal history', async ({ page }) => {
  const fixture = await workspace(page)
  fixture.latest = { state: 'FAILED', attempt: 1, step_count: 6 }
  await page.goto('/modules/workspace')
  await selectSession(page)
  const dialog = await openReview(page)
  await expect(dialog.getByText(proposal.title)).toBeVisible()
  await expect(dialog.getByRole('button', { name: '批准', exact: true })).toBeVisible()
  await expect(dialog.getByText('任务执行失败')).toHaveCount(0)
  expect(fixture.latestSessions).toHaveLength(0)
})

test('after reload the reviewer can follow a consumed approval to completion', async ({ page }) => {
  const fixture = await workspace(page)
  await page.goto('/modules/workspace')
  await selectSession(page)
  const dialog = await openReview(page)
  await dialog.getByRole('button', { name: '批准', exact: true }).click()
  await expect(dialog.getByRole('button', { name: '撤销批准' })).toBeVisible()
  fixture.active = null
  fixture.latest = { state: 'RUNNING', attempt: 2, step_count: 5 }

  await page.reload()
  await selectSession(page)
  await openReview(page)
  await expect(dialog.getByText('任务执行中')).toBeVisible()
  await expect(dialog.getByText(proposal.content)).toHaveCount(0)
  await expect(dialog.getByRole('button', { name: '撤销批准' })).toHaveCount(0)

  fixture.latest = { state: 'RETRY_WAIT', attempt: 2, step_count: 6 }
  await dialog.getByRole('button', { name: '查询执行进度' }).click()
  await expect(dialog.getByText('任务等待重试')).toBeVisible()
  fixture.latest = { state: 'SUCCEEDED', attempt: 3, step_count: 7 }
  await dialog.getByRole('button', { name: '查询执行进度' }).click()
  await expect(dialog.getByText('任务执行完成')).toBeVisible()
  await expect(dialog.getByText('7', { exact: true })).toBeVisible()
  expect(fixture.latestSessions).toEqual(Array(3).fill(firstSession.sessionNo))
  expect(fixture.progressReads).toBe(0)
  expect(fixture.decisions).toHaveLength(1)
})
