import { expect, test, type Page, type WebSocketRoute } from '@playwright/test'
import type { RealtimeMessage } from '../src/utils/realtime'

const sessionNo = 'VISITOR_SYNC'
const messages = [
  { msgId: '1', seq: 1, content: '接通前消息' },
  { msgId: '2', seq: 2, content: '漏掉的消息' },
  { msgId: '3', seq: 3, content: '最新消息' },
].map((item) => ({ ...item, msgNo: item.msgId, senderType: 2, msgType: 1, sendTime: '2026-11-23T10:00:00Z' }))

function push(socket: WebSocketRoute, type: string, data?: unknown, id?: string) {
  socket.send(JSON.stringify({ type, sessionNo, data, clientMsgNo: id }))
}

async function openVisitor(page: Page, onJoin: (socket: WebSocketRoute, connection: number) => void) {
  const sockets: WebSocketRoute[] = []
  const requests: RealtimeMessage[] = []
  await page.route('/api/customer/sessions/open', (route) => route.fulfill({
    json: { code: 0, data: {
      sessionNo, visitorToken: 'synthetic-visitor', visitorIdentityToken: 'synthetic-history',
      sessionStatus: 2, created: true,
    } },
  }))
  await page.routeWebSocket('**/ws/realtime?*', (socket) => {
    sockets.push(socket)
    socket.onMessage((raw) => {
      const frame = JSON.parse(String(raw)) as RealtimeMessage
      if (frame.type === 'PING') push(socket, 'PONG')
      else requests.push(frame)
    })
    push(socket, 'CONNECTED')
    onJoin(socket, sockets.length)
  })
  await page.goto('/visitor?appId=synthetic-channel')
  await expect(page.getByText(messages[0].content, { exact: true })).toBeVisible()
  return { sockets, requests }
}

for (const reconnect of [false, true]) {
  test(`visitor fills a gap ${reconnect ? 'after reconnect' : 'during a live push'} in order`, async ({ page }) => {
    const { sockets, requests } = await openVisitor(page, (socket, number) => {
      const initial = number === 1
      push(socket, 'JOINED', { messages: [messages[initial ? 0 : 2]], lastSeq: initial ? 1 : 3 })
    })
    if (reconnect) await sockets[0].close()
    else push(sockets[0], 'MESSAGE', messages[2])
    await expect.poll(() => requests.filter((request) => request.type === 'SYNC')).toEqual([
      { type: 'SYNC', sessionNo, data: { afterSeq: 1 } },
    ])
    const socket = sockets.at(-1)!
    push(socket, 'SYNCED', { afterSeq: 1, messages: messages.slice(1), hasMore: false })
    await expect(page.locator('.v-content')).toHaveText(messages.map((item) => item.content))
  })
}

test('visitor retries an unacknowledged message with the same id after reconnect', async ({ page }) => {
  const { sockets, requests } = await openVisitor(page, (socket) => {
    push(socket, 'JOINED', { messages: [messages[0]], lastSeq: 1 })
  })
  const content = '请创建一张新的测试工单'
  await page.getByRole('textbox').fill(content)
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await expect.poll(() => requests.filter((request) => request.type === 'SEND').length).toBe(1)
  const original = requests.find((request) => request.type === 'SEND')!
  expect(original).toMatchObject({ sessionNo, content, msgType: 1 })
  await sockets[0].close()
  await expect.poll(() => requests.filter((request) => request.type === 'SEND')).toEqual([original, original])
  const socket = sockets[1]
  const saved = { ...messages[1], senderType: 1, content, clientMsgNo: original.clientMsgNo }
  push(socket, 'ACK', saved, original.clientMsgNo)
  push(socket, 'MESSAGE', saved)
  await expect(page.locator('.v-content')).toHaveText([messages[0].content, content])
  await expect(page.getByText('发送中…', { exact: true })).toHaveCount(0)
})

test('visitor advances the cursor by the returned page and stops at a visibility gap', async ({ page }) => {
  const { sockets, requests } = await openVisitor(page, (socket) => {
    push(socket, 'JOINED', { messages: [messages[0]], lastSeq: 1 })
  })
  const socket = sockets[0]
  const latest = { ...messages[2], seq: 105, msgId: '105' }
  push(socket, 'MESSAGE', latest)
  const cursors = () => requests.filter((request) => request.type === 'SYNC').map((request) => request.data.afterSeq)
  await expect.poll(cursors).toEqual([1])
  const pageOfHistory = Array.from({ length: 100 }, (_, index) => ({
    ...messages[1], seq: index + 2, msgId: String(index + 2), content: `补拉消息 ${index + 2}`,
  }))
  push(socket, 'SYNCED', { afterSeq: 1, messages: pageOfHistory, hasMore: true })
  await expect.poll(cursors).toEqual([1, 101])
  push(socket, 'SYNCED', { afterSeq: 101, messages: [latest], hasMore: false })
  await expect(page.locator('.v-content')).toHaveCount(102)
  push(socket, 'MESSAGE', { ...latest, seq: 106, msgId: '106', content: '补拉完成后消息' })
  await expect(page.locator('.v-content').last()).toHaveText('补拉完成后消息')
  expect(cursors()).toEqual([1, 101])
})

test('visitor sees the confirmed ticket number once after reconnect', async ({ page }) => {
  const content = '工单已创建，编号：TICKET123'
  const notice = { ...messages[1], senderType: 4, msgType: 5, content }
  const { sockets, requests } = await openVisitor(page, (socket, number) => {
    const initial = number === 1
    push(socket, 'JOINED', { messages: initial ? [messages[0]] : [notice], lastSeq: initial ? 1 : 2 })
  })
  await expect(page.getByText(content, { exact: true })).toHaveCount(0)
  await sockets[0].close()
  await expect.poll(() => requests.filter((request) => request.type === 'SYNC')).toHaveLength(1)
  push(sockets[1], 'SYNCED', { afterSeq: 1, messages: [notice], hasMore: false })
  await expect(page.locator('.v-system')).toHaveText([content])
  await expect(page.getByText(messages[0].content, { exact: true })).toBeVisible()
})

test('online visitor sees a pushed ticket notice once even if delivered again', async ({ page }) => {
  const content = '工单已创建，编号：ONLINE123'
  const notice = { ...messages[1], senderType: 4, msgType: 5, content }
  const { sockets, requests } = await openVisitor(page, (socket) => {
    push(socket, 'JOINED', { messages: [messages[0]], lastSeq: 1 })
  })
  push(sockets[0], 'MESSAGE', notice)
  push(sockets[0], 'MESSAGE', notice)
  await expect(page.locator('.v-system')).toHaveText([content])
  expect(sockets).toHaveLength(1)
  expect(requests.filter((request) => request.type === 'SYNC')).toHaveLength(0)
})
