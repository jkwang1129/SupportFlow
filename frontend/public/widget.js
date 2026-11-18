/*!
 * SupportFlow 智能客服 · 官网在线客服挂件
 *
 * 用法（把这一行贴到官网页面里，最好放在 </body> 前）：
 *   <script src="https://你的前端域名/widget.js" data-app-id="渠道密钥" async></script>
 *
 * 可选属性：
 *   data-host        前端地址，默认取脚本自身所在域名（前后端不同域时用）
 *   data-title       挂件标题，默认「在线客服」
 *   data-color       主色，默认 #2563eb
 *   data-auto-open   设为 1 时打开页面就展开会话窗口
 *
 * 说明：data-app-id 是渠道的公开应用标识（App ID），不是密钥；
 * 真正的访问控制在服务端（渠道域名白名单 + 开会话限流 + 访客身份令牌）。
 */
(function () {
  'use strict'

  if (window.__supportflowWidgetLoaded) {
    return
  }
  window.__supportflowWidgetLoaded = true

  var script = document.currentScript || document.querySelector('script[data-app-id]')
  if (!script) {
    console.error('[supportflow-widget] 没找到挂件脚本标签，请检查引入方式')
    return
  }

  var appId = script.getAttribute('data-app-id')
  if (!appId) {
    console.error('[supportflow-widget] 缺少 data-app-id，无法确定是哪个渠道')
    return
  }

  var host = script.getAttribute('data-host')
  if (!host) {
    try {
      host = new URL(script.src, window.location.href).origin
    } catch (e) {
      host = window.location.origin
    }
  }
  host = host.replace(/\/+$/, '')

  var title = script.getAttribute('data-title') || '在线客服'
  var color = script.getAttribute('data-color') || '#2563eb'
  var autoOpen = script.getAttribute('data-auto-open') === '1'

  var PANEL_WIDTH = 380
  var PANEL_HEIGHT = 600
  var panel = null
  var frame = null
  var launcher = null

  function styles(element, rules) {
    element.style.cssText = rules
    return element
  }

  /** 会话窗口按需创建：客户点开时才建立长连接，避免每个访客都白连一次 */
  function ensurePanel() {
    if (panel) {
      return panel
    }
    panel = styles(document.createElement('div'), [
      'position:fixed',
      'right:24px',
      'bottom:100px',
      'width:' + PANEL_WIDTH + 'px',
      'height:' + PANEL_HEIGHT + 'px',
      'max-width:calc(100vw - 32px)',
      'max-height:calc(100vh - 140px)',
      'background:#fff',
      'border-radius:14px',
      'overflow:hidden',
      'box-shadow:0 18px 48px rgba(15,23,42,.24)',
      'z-index:2147483000',
      'display:none',
    ].join(';'))
    panel.setAttribute('data-supportflow-widget', 'panel')

    var header = styles(document.createElement('div'), [
      'display:flex',
      'align-items:center',
      'justify-content:space-between',
      'gap:12px',
      'padding:10px 14px',
      'background:' + color,
      'color:#fff',
      'font:600 14px/1.4 -apple-system,BlinkMacSystemFont,"PingFang SC","Microsoft YaHei",sans-serif',
    ].join(';'))

    var label = document.createElement('span')
    label.textContent = title

    var closeButton = styles(document.createElement('button'), [
      'border:0',
      'background:transparent',
      'color:#fff',
      'font-size:20px',
      'line-height:1',
      'cursor:pointer',
      'padding:0 2px',
    ].join(';'))
    closeButton.type = 'button'
    closeButton.textContent = '×'
    closeButton.setAttribute('aria-label', '关闭会话窗口')
    closeButton.addEventListener('click', function () {
      toggle(false)
    })

    header.appendChild(label)
    header.appendChild(closeButton)

    frame = styles(document.createElement('iframe'), [
      'width:100%',
      'height:calc(100% - 41px)',
      'border:0',
      'display:block',
    ].join(';'))
    frame.title = title
    frame.setAttribute('allow', 'clipboard-write')
    frame.src = host + '/visitor?appId=' + encodeURIComponent(appId) + '&embed=1'

    panel.appendChild(header)
    panel.appendChild(frame)
    document.body.appendChild(panel)
    return panel
  }

  function toggle(open) {
    var node = ensurePanel()
    node.style.display = open ? 'block' : 'none'
    if (launcher) {
      launcher.setAttribute('aria-expanded', open ? 'true' : 'false')
    }
    if (open) {
      // 每次打开都回到最新消息（iframe 里的会话本身是持续的）
      try {
        frame.contentWindow.postMessage({ type: 'supportflow:focus' }, '*')
      } catch (e) {
        /* 跨域场景忽略 */
      }
    }
  }

  function buildLauncher() {
    launcher = styles(document.createElement('button'), [
      'position:fixed',
      'right:24px',
      'bottom:24px',
      'width:56px',
      'height:56px',
      'border:0',
      'border-radius:50%',
      'background:' + color,
      'color:#fff',
      'cursor:pointer',
      'box-shadow:0 10px 24px rgba(37,99,235,.36)',
      'z-index:2147483000',
      'display:flex',
      'align-items:center',
      'justify-content:center',
      'transition:transform .18s ease',
    ].join(';'))
    launcher.type = 'button'
    launcher.setAttribute('data-supportflow-widget', 'launcher')
    launcher.setAttribute('aria-label', title)
    launcher.setAttribute('aria-expanded', 'false')
    launcher.innerHTML =
      '<svg width="26" height="26" viewBox="0 0 24 24" fill="none" aria-hidden="true">' +
      '<path d="M12 3C7 3 3 6.4 3 10.6c0 2.3 1.2 4.4 3.2 5.8-.1 1-.5 2-1.2 2.9-.2.3 0 .7.3.7 1.9 0 3.4-.7 4.4-1.4.7.1 1.5.2 2.3.2 5 0 9-3.4 9-7.6S17 3 12 3z" fill="#fff"/></svg>'
    launcher.addEventListener('click', function () {
      var opened = launcher.getAttribute('aria-expanded') === 'true'
      toggle(!opened)
    })
    launcher.addEventListener('mouseenter', function () {
      launcher.style.transform = 'translateY(-2px)'
    })
    launcher.addEventListener('mouseleave', function () {
      launcher.style.transform = 'none'
    })
    document.body.appendChild(launcher)
  }

  function mount() {
    buildLauncher()
    if (autoOpen) {
      toggle(true)
    }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', mount)
  } else {
    mount()
  }
})()
