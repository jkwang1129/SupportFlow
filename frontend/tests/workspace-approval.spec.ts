import { expect, test, type Page, type Route } from '@playwright/test'
import type { ApprovalEvent, ApprovalProgress, ApprovalRunReference } from '../src/api/customer/agentApproval'

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

function event(seq: number, event_type: ApprovalEvent['event_type'], step_no: number | null = null): ApprovalEvent {
  return { seq, event_type, occurred_at: '2026-11-24T08:00:00Z', step_no, outcome: null }
}

function eventPage(route: Route, after: number, events: ApprovalEvent[], hasMore = false) {
  return reply(route, { schema_version: 'agent-run-events-v1', run_id: reference.run_id,
    next_seq: events.at(-1)?.seq ?? after, has_more: hasMore, events })
}

async function workspace(page: Page, role = 'ADMIN') {
  const fixture = {
    active: 'PENDING' as 'PENDING' | 'APPROVED' | null,
    previewStatus: 200,
    progressStatus: 200,
    progress: { state: 'PENDING', attempt: 1, step_count: 4 } as ApprovalProgress,
    progressUnavailable: false,
    progressReads: 0,
    latest: null as (ApprovalProgress & { timeline?: ApprovalRunReference }) | null,
    latestStatus: 200,
    latestSessions: [] as string[],
    latestIncludesTimeline: [] as boolean[],
    timelineReads: [] as number[],
    timelineEvents: [] as ApprovalEvent[],
    timelineReply: undefined as ((route: Route, after: number) => Promise<void>) | undefined,
    activeSessions: [] as string[],
    decisions: [] as unknown[],
    cancellation: { state: 'CANCELLED', attempt: 1, step_count: 4 } as ApprovalProgress,
    cancellationUnavailable: false,
    cancellations: 0,
  }
  await page.addInitScript(() => {
    localStorage.setItem('supportflow_admin_token', 'test-workspace-user')
    localStorage.setItem('supportflow_admin_tenant', 'TEST_APPROVAL')
    localStorage.setItem('supportflow_onboard_done', 'true')
  })
  await page.routeWebSocket('**/ws/realtime?*', () => {})
  await page.route('/api/**', async (route) => {
    const { pathname: path, searchParams } = new URL(route.request().url())
    if (path.startsWith(`${approvalPath}/sessions/`) && path.endsWith('/active')) {
      const sessionNo = path.split('/').at(-2)!
      fixture.activeSessions.push(sessionNo)
      return reply(route, sessionNo === firstSession.sessionNo && fixture.active
        ? { ...reference, status: fixture.active } : null)
    }
    if (path.startsWith(`${approvalPath}/sessions/`) && path.endsWith('/latest-progress')) {
      const sessionNo = path.split('/').at(-2)!
      fixture.latestSessions.push(sessionNo)
      fixture.latestIncludesTimeline.push(searchParams.get('includeTimeline') === 'true')
      if (fixture.latestStatus !== 200) return route.fulfill({ status: fixture.latestStatus, json: {} })
      return reply(route, sessionNo === firstSession.sessionNo ? fixture.latest : null)
    }
    if (path === `${eventsPath}/timeline`) {
      const after = Number(searchParams.get('afterSeq'))
      fixture.timelineReads.push(after)
      if (fixture.timelineReply) return fixture.timelineReply(route, after)
      const rows = fixture.timelineEvents.filter(row => row.seq > after)
      return eventPage(route, after, rows.slice(0, 16), rows.length > 16)
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
    if (path === `${eventsPath}/cancel` && route.request().method() === 'POST') {
      fixture.cancellations++
      if (fixture.cancellationUnavailable) return route.abort('internetdisconnected')
      return reply(route, fixture.cancellation)
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

test('reconnects from the accepted cursor and renders an overlapping event once', async ({ page, context }) => {
  await page.clock.install()
  const fixture = await workspace(page)
  fixture.timelineEvents = [event(1, 'run_started'), event(2, 'approval_required')]
  await page.goto('/modules/workspace')
  await selectSession(page)
  const dialog = await openReview(page)
  const timeline = dialog.getByRole('region', { name: '任务事件' })
  await expect(timeline.getByText('等待坐席审批')).toBeVisible()
  expect(fixture.timelineReads).toEqual([0])
  await dialog.getByRole('button', { name: '批准', exact: true }).click()
  await expect(dialog.getByRole('button', { name: '撤销批准' })).toBeVisible()
  await context.setOffline(true)
  await expect(timeline.getByText(/网络连接已中断/)).toBeVisible()
  await page.clock.runFor(6000)
  expect(fixture.timelineReads).toEqual([0])

  fixture.timelineReply = (route, after) => eventPage(route, after, [event(2, 'approval_required'), event(3, 'tool_started', 2)])
  await context.setOffline(false)
  await expect(timeline.getByText(/工具调用开始/)).toBeVisible()
  expect(fixture.timelineReads).toEqual([0, 2])
  await expect(timeline.getByText('等待坐席审批')).toHaveCount(1)
  await expect(timeline.getByRole('listitem')).toHaveCount(3)

  fixture.timelineReply = (route, after) => eventPage(route, after, [event(4, 'run_completed')])
  fixture.progressUnavailable = true
  await page.clock.runFor(2000)
  await expect(dialog.getByText('任务执行完成')).toBeVisible()
  await expect(timeline.getByText('任务结束 · 成功')).toBeVisible()
  await expect(dialog.getByRole('button', { name: '撤销批准' })).toHaveCount(0)
  await expect(dialog.getByText(proposal.content)).toHaveCount(0)
  await page.clock.runFor(6000)
  expect(fixture.timelineReads).toEqual([0, 2, 3])
  expect(fixture.decisions).toHaveLength(1)
})

test('does not overlap a slow page and aborts it before watching another session', async ({ page }) => {
  await page.clock.install()
  const fixture = await workspace(page)
  let release!: () => void
  const held = new Promise<void>(resolve => { release = resolve })
  fixture.timelineReply = async (route, after) => {
    await held
    await eventPage(route, after, [event(1, 'run_started')]).catch(() => {})
  }
  await page.goto('/modules/workspace')
  await selectSession(page)
  const dialog = await openReview(page)
  try {
    await expect.poll(() => fixture.timelineReads).toEqual([0])
    await page.clock.runFor(6000)
    expect(fixture.timelineReads).toEqual([0])
    const cancelled = page.waitForEvent('requestfailed', request => request.url().includes(`${eventsPath}/timeline`))
    await dialog.getByRole('button', { name: '关闭', exact: true }).click()
    await cancelled
    await expect(dialog).not.toBeVisible()
    await selectSession(page, secondSession.customerName)
    await openReview(page)
    await expect(dialog.getByText('当前会话没有可审查的工单提案。')).toBeVisible()
  } finally {
    release()
  }
  await page.clock.runFor(6000)
  await expect(dialog.getByRole('region', { name: '任务事件' })).toHaveCount(0)
  await expect(dialog.getByText('任务开始')).toHaveCount(0)
  expect(fixture.timelineReads).toEqual([0])
})

test('rediscovers the Run after reload and clears its events when scope is revoked', async ({ page }) => {
  await page.clock.install()
  const fixture = await workspace(page)
  fixture.active = null
  fixture.latest = { state: 'RUNNING', attempt: 2, step_count: 5,
    timeline: { source_event_id: reference.source_event_id, run_id: reference.run_id } }
  fixture.timelineEvents = [event(1, 'run_started')]
  await page.goto('/modules/workspace')
  await page.reload()
  await selectSession(page)
  const dialog = await openReview(page)
  await expect(dialog.getByText('任务执行中')).toBeVisible()
  await expect(dialog.getByText('任务开始')).toBeVisible()
  expect(fixture.latestIncludesTimeline).toEqual([true])
  expect(fixture.timelineReads).toEqual([0])

  fixture.timelineReply = route => route.fulfill({ status: 410, json: {} })
  await page.clock.runFor(2000)
  await expect(dialog.getByText('当前工单任务的来源已过期。')).toBeVisible()
  await expect(dialog.getByText('任务开始')).toHaveCount(0)
  await expect(dialog.getByText('任务执行中')).toHaveCount(0)
  await page.clock.runFor(6000)
  expect(fixture.timelineReads).toEqual([0, 1])
})

test('retries a temporary error with the same cursor and stops at a sequence gap', async ({ page }) => {
  await page.clock.install()
  const fixture = await workspace(page)
  fixture.timelineEvents = [event(1, 'run_started')]
  await page.goto('/modules/workspace')
  await selectSession(page)
  const dialog = await openReview(page)
  await expect(dialog.getByText('任务开始')).toBeVisible()
  fixture.timelineReply = route => route.fulfill({ status: 503, json: {} })
  await page.clock.runFor(2000)
  await expect(dialog.getByText(/正在重试/)).toBeVisible()
  fixture.timelineReply = (route, after) => eventPage(route, after, [event(3, 'run_completed')])
  await page.clock.runFor(2000)
  await expect(dialog.getByText(/请刷新审批窗口/)).toBeVisible()
  await expect(dialog.getByText('任务结束 · 成功')).toHaveCount(0)
  await page.clock.runFor(12000)
  expect(fixture.timelineReads).toEqual([0, 1, 1])
})

test('drains a multi-page history and keeps the display bounded', async ({ page }) => {
  await page.clock.install()
  const fixture = await workspace(page)
  fixture.active = null
  fixture.latest = { state: 'SUCCEEDED', attempt: 2, step_count: 33,
    timeline: { source_event_id: reference.source_event_id, run_id: reference.run_id } }
  fixture.timelineEvents = [event(1, 'run_started'),
    ...Array.from({ length: 33 }, (_, index) => event(index + 2, 'step_started', index + 1)),
    event(35, 'run_completed')]
  await page.goto('/modules/workspace')
  await selectSession(page)
  const dialog = await openReview(page)
  const timeline = dialog.getByRole('region', { name: '任务事件' })
  await expect(timeline.getByText('任务结束 · 成功')).toBeVisible()
  await expect(timeline.getByRole('listitem')).toHaveCount(32)
  await expect(timeline.getByRole('list')).toHaveAttribute('start', '4')
  await page.clock.runFor(6000)
  expect(fixture.timelineReads).toEqual([0, 16, 32])
  expect(fixture.progressReads).toBe(0)
})

test('requires confirmation and sends one cancellation for an approved task', async ({ page }) => {
  const fixture = await workspace(page)
  await page.goto('/modules/workspace')
  await selectSession(page)
  const dialog = await openReview(page)
  await expect(dialog.getByRole('button', { name: '取消任务', exact: true })).toHaveCount(0)
  await dialog.getByRole('button', { name: '批准', exact: true }).click()
  const cancel = dialog.getByRole('button', { name: '取消任务', exact: true })
  await cancel.click()
  await page.getByRole('button', { name: '继续执行', exact: true }).click()
  expect(fixture.cancellations).toBe(0)
  await cancel.click()
  await page.getByRole('button', { name: '确认取消', exact: true }).click()
  await expect(dialog.getByText('任务已取消')).toBeVisible()
  await expect(cancel).toHaveCount(0)
  expect(fixture.cancellations).toBe(1)
  expect(fixture.decisions).toHaveLength(1)
})

const cancellationStates: [ApprovalProgress, string][] = [
  [{ state: 'SUCCEEDED', attempt: 2, step_count: 7 }, '任务执行完成'],
  [{ state: 'FAILED', attempt: 2, step_count: 7, write_status: 'UNCERTAIN' }, '工单写入结果待人工核查'],
  [{ state: 'RUNNING', attempt: 2, step_count: 7, write_status: 'COMMITTED' }, '工单写入已确认，任务仍在收尾'],
]
for (const [state, label] of cancellationStates) {
  test(`shows the observed cancellation outcome: ${label}`, async ({ page }) => {
    const fixture = await workspace(page)
    fixture.active = null
    fixture.latest = { state: 'RUNNING', attempt: 2, step_count: 6,
      timeline: { source_event_id: reference.source_event_id, run_id: reference.run_id } }
    fixture.cancellation = state
    await page.goto('/modules/workspace')
    await selectSession(page)
    const dialog = await openReview(page)
    await expect(dialog.getByText('任务执行中')).toBeVisible()
    await dialog.getByRole('button', { name: '取消任务', exact: true }).click()
    await page.getByRole('button', { name: '确认取消', exact: true }).click()
    await expect(dialog.getByText(label)).toBeVisible()
    await expect(dialog.getByText('任务已取消')).toHaveCount(0)
    await expect(dialog.getByRole('button', { name: '取消任务', exact: true })).toHaveCount(0)
    expect(fixture.cancellations).toBe(1)
    expect(fixture.decisions).toHaveLength(0)
    if (state.write_status === 'UNCERTAIN') {
      fixture.latest = { ...state, timeline: fixture.latest.timeline }
      await page.reload()
      await selectSession(page)
      await openReview(page)
      await expect(dialog.getByText(label)).toBeVisible()
    }
  })
}

test('does not retry a cancellation after a lost response', async ({ page }) => {
  const fixture = await workspace(page)
  fixture.active = 'APPROVED'
  fixture.cancellationUnavailable = true
  fixture.progress = { state: 'CANCELLED', attempt: 1, step_count: 4 }
  await page.goto('/modules/workspace')
  await selectSession(page)
  const dialog = await openReview(page)
  await dialog.getByRole('button', { name: '取消任务', exact: true }).click()
  await page.getByRole('button', { name: '确认取消', exact: true }).click()
  await expect(page.getByText('暂未确认取消结果，请先查询执行进度。')).toBeVisible()
  await expect(dialog.getByText('任务已取消')).toHaveCount(0)
  await dialog.getByRole('button', { name: '查询执行进度', exact: true }).click()
  await expect(dialog.getByText('任务已取消')).toBeVisible()
  expect(fixture.cancellations).toBe(1)
  expect(fixture.progressReads).toBe(1)
})
