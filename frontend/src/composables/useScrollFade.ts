import { onBeforeUnmount, watch, type Ref } from 'vue'

/** 元素本身，或 Element Plus 组件实例（ref 绑在 <el-aside> 这类组件上时拿到的是实例） */
type ScrollTarget = HTMLElement | { $el?: unknown } | null | undefined

/**
 * 把 ref 的值收敛成真正的 DOM 元素。
 *
 * <p>踩过的坑：ref 绑在 `<el-aside ref="asideRef">` 上时拿到的是**组件实例**，
 * 它没有 addEventListener，直接调用会抛 "node.addEventListener is not a function"
 * （而且是在路由跳转的 watch 里抛，报错栈看着跟滚动条毫无关系）。</p>
 */
function resolveElement(value: ScrollTarget): HTMLElement | null {
  if (!value) {
    return null
  }
  if (typeof (value as HTMLElement).addEventListener === 'function') {
    return value as HTMLElement
  }
  const inner = (value as { $el?: unknown }).$el
  if (inner && typeof (inner as HTMLElement).addEventListener === 'function') {
    return inner as HTMLElement
  }
  return null
}

/**
 * 滚动条"用时出现、闲时隐去"。
 *
 * <p>滚动中给容器加 `is-scrolling`（配合 pro-scroll 的样式让滑块变明显），
 * 停 700ms 后移除——这是聊天窗口在大厂产品里的默认手感：滚动条不常驻，
 * 但需要它的时候一定看得见。</p>
 *
 * <p>监听用 passive，不参与滚动性能；容器可能是 v-if 之后才出现的，
 * 所以用 watch 而不是只在 onMounted 绑一次。</p>
 */
/** 容器可能是 v-if 之后才出现，所以允许 null / undefined */
export function useScrollFade(target: Ref<ScrollTarget> | Ref<HTMLElement | null | undefined>, idleMs = 700) {
  let node: HTMLElement | null = null
  let timer = 0

  const onScroll = () => {
    if (!node) {
      return
    }
    node.classList.add('is-scrolling')
    if (timer) {
      window.clearTimeout(timer)
    }
    timer = window.setTimeout(() => {
      node?.classList.remove('is-scrolling')
    }, idleMs)
  }

  watch(target as Ref<ScrollTarget>, (value, previous) => {
    const next = resolveElement(value)
    const old = resolveElement(previous)
    if (old && old !== next) {
      old.removeEventListener('scroll', onScroll)
      old.classList.remove('is-scrolling')
    }
    node = next
    if (node) {
      node.addEventListener('scroll', onScroll, { passive: true })
      // 挂载时通常内容还没满，先不显示；滚动才出现
      node.classList.remove('is-scrolling')
    }
  }, { immediate: true, flush: 'post' })

  onBeforeUnmount(() => {
    if (timer) {
      window.clearTimeout(timer)
    }
    node?.removeEventListener('scroll', onScroll)
  })
}
