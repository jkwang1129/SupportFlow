import { ElMessage } from 'element-plus'

/**
 * 统一错误提示：同一件事只提示一次。
 *
 * <p><b>为什么要它</b>：后端业务错误走的是「HTTP 200 + code≠0」，`request()` 已经弹过一次全局
 * 提示，页面 catch 里再 `ElMessage.error(e.message)` 就是同一条消息弹两遍——用户看到的是
 * "重复提示了几次"（连点两下就是四条）。所以 `request()` 弹完给错误打上标记，页面一律改用
 * `errorMessage(e, '兜底文案')`：全局弹过的直接吞掉，没弹过（页面自己抛的错、silent 探测接口）
 * 才由页面弹。</p>
 *
 * <p>用法：`catch (e) { errorMessage(e, '保存失败') }`。</p>
 */
const REPORTED = Symbol('supportflow.error.reported')

type Marked = { [REPORTED]?: boolean }

/** `request()` 弹完全局提示后调用，把这条错误标记成"已经说过了" */
export function markReported(error: unknown): void {
  if (error && (typeof error === 'object' || typeof error === 'function')) {
    try {
      ;(error as Marked)[REPORTED] = true
    } catch {
      // 对象被冻结等极端情况：标记失败就退回"可能弹两次"，不能因此把主流程抛断
    }
  }
}

/** 这条错误是不是已经在全局提示里说过了 */
export function isReported(error: unknown): boolean {
  return Boolean(error && (error as Marked)[REPORTED])
}

/** 页面 catch 里的统一提示：全局弹过就不再重复弹 */
export function errorMessage(error: unknown, fallback = '操作失败，请稍后重试'): void {
  if (isReported(error)) {
    return
  }
  const text = typeof error === 'string' && error
    ? error
    : error instanceof Error && error.message
      ? error.message
      : fallback
  ElMessage.error(text)
}
