<template>
  <div class="docs-page">
    <header class="doc-head">
      <div>
        <h2 class="doc-title">渠道接入文档</h2>
        <p class="doc-sub">
          SupportFlow 智能客服 · 六种渠道的接入方式、开放接口与安全规范
          <span class="doc-ver">v1.0 · 更新于 2026-09-24</span>
        </p>
      </div>
      <div class="doc-head-actions">
        <el-button @click="router.push('/channels')">返回渠道接入</el-button>
        <el-button @click="router.push('/channel-demos')">渠道效果演示</el-button>
      </div>
    </header>

    <div class="doc-body">
      <aside class="doc-nav">
        <div class="dn-title">文档目录</div>
        <a
          v-for="item in sections"
          :key="item.id"
          href="javascript:void(0)"
          :class="{ on: active === item.id }"
          @click="go(item.id)"
        >
          {{ item.title }}
        </a>
        <div class="dn-tip">
          文档里的地址、接口路径、错误码都取自本系统当前实现，可对照控制台联调使用。
        </div>
      </aside>

      <main class="doc-main">
        <section id="doc-overview" class="doc-sec">
          <h3>一、接入总览</h3>
          <p>
            接入一个渠道是四步：<b>创建渠道 → 获取 AppID → 集成到你的系统 → 联调上线</b>。
            每个渠道有独立的 AppID（渠道应用标识），会话、路由、质检、统计都按渠道维度隔离。
          </p>
          <div class="steps">
            <div class="step"><span class="n">1</span><div><b>创建渠道</b><p>控制台「渠道接入 → 新增渠道」，选渠道类型并命名（例如「官网在线客服」）。</p></div></div>
            <div class="step"><span class="n">2</span><div><b>获取 AppID</b><p>创建后自动生成，在「查看密钥」里复制；AppID 是公开标识，不是密钥。</p></div></div>
            <div class="step"><span class="n">3</span><div><b>集成</b><p>按各渠道说明接入：网站挂件一行脚本，公众号 / 小程序用 H5 客服页。</p></div></div>
            <div class="step"><span class="n">4</span><div><b>联调上线</b><p>用「测试会话」以访客身份开一条真实会话，坐席工作台能收到即算通。</p></div></div>
          </div>

          <h4>渠道能力对照</h4>
          <p class="note">
            状态口径：<b>已开放</b> = 原生通道已经产品化，可直接上线对客（配置入口：网站渠道用控制台「渠道效果演示 / 查看密钥」，
            公众号 / 小程序用微信配置，App / 400 / 邮件用渠道列表里的「原生接入配置」）。
          </p>
          <table class="doc-table">
            <thead>
              <tr><th>渠道</th><th>接入方式</th><th>当前状态</th><th>说明</th></tr>
            </thead>
            <tbody>
              <tr><td>网站在线客服</td><td>widget.js 挂件 / 直链 / iframe / 自建 UI</td><td><span class="tag ok">已开放</span></td><td>一行脚本即可获得右下角悬浮客服</td></tr>
              <tr><td>微信公众号</td><td>原生接入（服务器回调 + 网页授权）<br />也可用菜单跳 H5</td><td><span class="tag ok">已开放</span></td><td>客户在公众号对话框直接发消息，机器人/坐席回复原路推回；模板消息支持物流/工单/优惠券</td></tr>
              <tr><td>微信小程序</td><td>原生客服消息（小程序后台消息推送）<br />也可用 web-view 内嵌 H5</td><td><span class="tag ok">已开放</span></td><td>客户在小程序点「在线客服」直接进微信客服会话，平台自动接管并原路回复</td></tr>
              <tr><td>官方 App</td><td>HTTP + 长连接接入，回复走企业推送服务</td><td><span class="tag ok">已开放</span></td><td>同一套会话 / 工单 / 质检链路；离线推送交给你的 APNs / FCM 服务</td></tr>
              <tr><td>400 电话热线</td><td>呼叫中心回调对接（呼入 / 按键 / 挂机）</td><td><span class="tag ok">已开放</span></td><td>语音导航按键分流、转人工进队列、挂机自动生成通话小结并建单</td></tr>
              <tr><td>邮件工单</td><td>邮件网关回调对接（进线 + 发信）</td><td><span class="tag ok">已开放</span></td><td>邮件进线自动建工单、秒级受理回执、规则识别诉求与优先级、解决后邮件回访</td></tr>
            </tbody>
          </table>

          <div class="callout info">
            <b>前置条件</b>：企业已完成开通（企业信息通过审核），当前账号有「渠道接入」权限；
            生产环境建议全站 HTTPS——微信内打开、小程序 web-view 都要求 HTTPS。
          </div>
        </section>

        <section id="doc-web" class="doc-sec">
          <h3>二、网站在线客服</h3>
          <p>网站渠道支持四种用法，按改造量从低到高排列。</p>

          <h4>2.1 一行挂件（推荐）</h4>
          <p>把下面这一行放在页面的 <code>&lt;/body&gt;</code> 之前，右下角就会出现客服入口；点开才建立长连接，不点不占资源。</p>
          <CodeBlock label="HTML" :code="code.webWidget" />
          <table class="doc-table">
            <thead><tr><th>属性</th><th>必填</th><th>说明</th></tr></thead>
            <tbody>
              <tr><td><code>data-app-id</code></td><td>是</td><td>渠道 AppID（控制台「渠道接入 → 查看密钥」）</td></tr>
              <tr><td><code>data-host</code></td><td>否</td><td>客服前端地址，默认取脚本自身域名；前后端不同域时填（如 <code>https://console.example.com</code>）</td></tr>
              <tr><td><code>data-title</code></td><td>否</td><td>窗口标题，默认「在线客服」</td></tr>
              <tr><td><code>data-color</code></td><td>否</td><td>主色，默认 <code>#2563eb</code></td></tr>
              <tr><td><code>data-auto-open</code></td><td>否</td><td>设为 <code>1</code> 时进页面自动展开会话窗口</td></tr>
            </tbody>
          </table>

          <h4>2.2 直接打开客服页</h4>
          <p>不想要悬浮按钮（例如放在「联系我们」页面里）时，直接给链接即可：</p>
          <CodeBlock label="URL" :code="code.visitorUrl" />
          <table class="doc-table">
            <thead><tr><th>参数</th><th>必填</th><th>说明</th></tr></thead>
            <tbody>
              <tr><td><code>appId</code></td><td>是</td><td>渠道 AppID（旧写法 <code>key</code> 仍兼容）</td></tr>
              <tr><td><code>name</code></td><td>否</td><td>访客名，坐席列表里直接看到，便于区分来源（如「官网访客-张先生」）</td></tr>
              <tr><td><code>embed=1</code></td><td>否</td><td>内嵌模式：隐藏页头，只留会话区</td></tr>
              <tr><td><code>fresh=1</code></td><td>否</td><td>清掉本地访客身份，当作新客户——<b>仅供联调</b></td></tr>
            </tbody>
          </table>

          <h4>2.3 iframe 内嵌</h4>
          <CodeBlock label="HTML" :code="code.webIframe" />

          <h4>2.4 自建 UI（完全自定义界面）</h4>
          <p>想用自己的聊天界面，就用开放接口：开会话 → 连长连接 → 收发消息，协议见「八、开放接口参考」。</p>

          <div class="callout warn">
            <b>注意</b>：站点配置了 CSP 时，要放行 <code>script-src</code>（挂件脚本）、
            <code>frame-src</code>（会话窗口）与 <code>connect-src</code>（wss 长连接）；
            广告拦截类插件也可能拦下 <code>widget.js</code>，联调先用无痕窗口排除。
          </div>
        </section>

        <section id="doc-wechat" class="doc-sec">
          <h3>三、微信公众号</h3>
          <p>
            <span class="tag ok">已开放</span>
            客户在公众号对话框里<b>直接发消息</b>，平台自动接管：机器人先答、需要时转人工，
            客服/机器人的回复<b>原路推回微信</b>；配合网页授权可以拿到 openid 识别微信身份，
            模板消息支持物流提醒、工单进展、优惠券到账。不想开放对话框权限时，也可以用「菜单跳 H5 客服页」这条路。
          </p>

          <h4>3.1 准备四项配置</h4>
          <p>在微信公众平台（需<b>已认证的服务号</b>）拿到下面四项，填到渠道的公众号配置里：</p>
          <table class="doc-table">
            <thead><tr><th>配置项</th><th>在哪拿</th><th>用途</th></tr></thead>
            <tbody>
              <tr><td><code>AppID</code></td><td>公众号后台 → 设置与开发 → 基本配置</td><td>渠道身份；回调按它路由</td></tr>
              <tr><td><code>AppSecret</code></td><td>同上（重置后只显示一次）</td><td>换 access_token、网页授权换 openid</td></tr>
              <tr><td><code>Token</code></td><td>自己起一个（1~32 位字母数字）</td><td>回调验签</td></tr>
              <tr><td><code>EncodingAESKey</code></td><td>后台随机生成（43 位）</td><td>安全模式加解密（推荐开启）</td></tr>
            </tbody>
          </table>

          <h4>3.2 配服务器回调（收消息）</h4>
          <ol class="doc-list">
            <li>公众号后台 → 设置与开发 → <b>服务器配置</b>：URL 填下面的回调地址，Token / EncodingAESKey 与上面一致，消息加解密方式选<b>安全模式</b>。</li>
            <li>点「提交」时微信会来验签并推一条事件，平台收到即会自动建档（关注者 → 客户档案）。</li>
            <li>把 <b>IP 白名单</b>（基本配置里）加上你部署环境的出口 IP，否则调用微信接口会报 <code>errcode 40164</code>。</li>
          </ol>
          <CodeBlock label="服务器地址(URL)" :code="code.wechatCallback" />

          <h4>3.3 配网页授权域名（认微信身份）</h4>
          <ol class="doc-list">
            <li>公众号后台 → 设置与开发 → 公众号设置 → 功能设置 → <b>网页授权域名</b>，填客服前端域名（需下载校验文件放到站点根目录）。</li>
            <li>菜单链接指向下面的授权入口，客户点开后会静默授权拿到 openid，把微信身份接到客户档案上（<code>snsapi_base</code> 不弹确认框）。</li>
          </ol>
          <CodeBlock label="菜单链接（推荐）" :code="code.wechatOauth" />
          <p>不想走授权（或公众号没有网页授权权限）时，也可以用菜单直接跳 H5 客服页：</p>
          <CodeBlock label="菜单地址（H5 方式）" :code="code.wechatMenu" />

          <h4>3.4 配模板消息（主动通知）</h4>
          <ol class="doc-list">
            <li>公众号后台 → 模板消息 → 从模板库添加：物流提醒、工单进展、优惠券到账各一个，复制模板 ID 填进渠道配置。</li>
            <li>字段名必须用微信规定的（申请模板时按这些字段选），否则发送会报 <code>errcode 47003</code>：</li>
          </ol>
          <table class="doc-table">
            <thead><tr><th>模板</th><th>字段</th></tr></thead>
            <tbody>
              <tr><td>物流提醒</td><td><code>character_string1</code> 单号 · <code>phrase2</code> 状态 · <code>thing3</code> 备注</td></tr>
              <tr><td>工单进展</td><td><code>character_string1</code> 工单号 · <code>thing2</code> 标题 · <code>phrase3</code> 状态 · <code>time4</code> 时间</td></tr>
              <tr><td>优惠券到账</td><td><code>thing1</code> 券名 · <code>amount2</code> 金额 · <code>time3</code> 有效期</td></tr>
            </tbody>
          </table>

          <h4>3.5 联调（不用等公众号审核）</h4>
          <p>
            渠道配置页里的<b>联调模拟器</b>会伪造一条"微信推来的消息"，走完全一样的链路
            （openid 绑定 → 开会话 → 落库 → 机器人接待），消息与会话可以直接在「在线客服」里看；
            模拟用的 openid 带 <code>sim_</code> 前缀，平台不会真的往微信发消息。
          </p>
          <div class="callout info">
            <b>常见错误码</b>：<code>40013</code> AppID 不对 · <code>40164</code> 调用方 IP 不在白名单 ·
            <code>40001/42001</code> access_token 失效（平台会自动刷新）· <code>47003</code> 模板字段不合规 ·
            <code>48001</code> 接口未授权（服务号需要在「接口权限」里确认）。
          </div>
        </section>

        <section id="doc-miniapp" class="doc-sec">
          <h3>四、微信小程序</h3>
          <p>
            <span class="tag ok">已开放</span>
            客户在小程序里点「在线客服」（<code>&lt;button open-type="contact"&gt;</code>）进入<b>微信原生客服会话</b>，
            消息由微信推送到平台，机器人 / 坐席的回复再原路推回客户微信——不需要 web-view、不需要 H5。
          </p>
          <h4>4.1 配置消息推送</h4>
          <ol class="doc-list">
            <li>在渠道列表创建「微信小程序」渠道，进配置页填写 AppID / AppSecret / Token / EncodingAESKey，把<b>平台类型</b>选为小程序。</li>
            <li>小程序后台 → 开发 → <b>开发设置 → 消息推送</b>：URL 填下面的回调地址，Token / EncodingAESKey 与上面一致，数据格式选 <b>JSON</b>，加解密方式选<b>安全模式</b>。</li>
            <li>小程序里放客服入口：<code>&lt;button open-type="contact"&gt;在线客服&lt;/button&gt;</code>（可放在「我的」页或悬浮按钮）。</li>
            <li>真机点客服入口发一句话：坐席工作台能看到，机器人回复也能在微信里收到，即接入成功。</li>
          </ol>
          <CodeBlock label="消息推送地址（URL）" :code="code.miniappCallback" />
          <CodeBlock label="小程序客服入口" :code="code.miniappWxml" />
          <div class="callout warn">
            <b>注意</b>：① 消息推送必须能公网访问（本地联调用内网穿透），且必须是 HTTPS；
            ② 微信要求 5 秒内响应，平台会立刻回 <code>success</code>、回复走客服消息接口异步推；
            ③ 同一条消息微信会重推，平台按 <code>MsgId</code> 去重，不会出现两条。
          </div>
          <div class="callout plan">
            <b>备选方式</b>：如果小程序暂时不想开消息推送，也可以用 <code>web-view</code> 内嵌客服页
            （需要在小程序后台配置业务域名），聊天能力与网站渠道一致。
          </div>
        </section>

        <section id="doc-app" class="doc-sec">
          <h3>五、官方 App</h3>
          <p>
            <span class="tag ok">已开放</span>
            App 侧可以用两条通道接：<b>HTTP 发消息</b>（简单、适合快速接入）与
            <b>WebSocket 长连接</b>（实时收消息，能力与访客端完全一致）。回复会推到你配置的<b>推送服务</b>，
            由它下发 APNs / FCM——推送证书留在你的 App 侧，不用交给客服平台。
          </p>
          <h4>5.1 配置</h4>
          <ol class="doc-list">
            <li>创建「官方 App」渠道，进「原生接入配置」页填写欢迎语、离线推送地址与推送签名密钥。</li>
            <li>把页面上的<b>消息回调地址</b>配到 App 的服务端（客户端不要内嵌密钥）。</li>
            <li>App 用户标识（你的 userId）由客户端带上，平台会给它绑定一个客户档案——同一个人再来还是同一条轨迹。</li>
          </ol>
          <CodeBlock label="发消息（服务端 / 客户端均可调用）" :code="code.appMessage" />
          <div class="callout info">
            <b>推送载荷</b>：平台会 POST <code>{ userId, title, body, sessionNo, bizType }</code> 到你的推送服务，
            请求头带 <code>X-SupportFlow-Timestamp</code> 与 <code>X-SupportFlow-Signature</code>（HMAC-SHA256），验签后再下发。
          </div>
        </section>

        <section id="doc-phone" class="doc-sec">
          <h3>六、400 电话热线</h3>
          <p>
            <span class="tag ok">已开放</span>
            平台对接<b>呼叫中心</b>：呼叫中心把「呼入 / 按键 / 挂机」事件回调给平台，
            平台返回要播报的文本与动作（<code>play</code> 播报 / <code>transfer</code> 转人工），
            挂机时自动生成通话小结并建工单（进入 SLA 计时）。
          </p>
          <h4>6.1 配置</h4>
          <ol class="doc-list">
            <li>创建「400 电话热线」渠道，进「原生接入配置」页填写<b>呼叫中心签名密钥</b>、<b>语音导航菜单</b>（JSON）与默认技能组。</li>
            <li>把页面上的<b>呼叫事件回调地址</b>配到呼叫中心（阿里云 / 容联 / 自建坐席台都可以）。</li>
            <li>用联调模拟器跑一遍「呼入 → 按键 1 → 挂机」，确认工单中心出现了通话小结工单。</li>
          </ol>
          <CodeBlock label="呼叫事件回调（呼叫中心 → 平台）" :code="code.phoneEvent" />
          <CodeBlock label="平台返回（平台 → 呼叫中心，照着执行 TTS / 转接）" :code="code.phoneReply" />
          <table class="doc-table">
            <thead><tr><th>事件</th><th>平台做什么</th></tr></thead>
            <tbody>
              <tr><td><code>incoming</code></td><td>来电号码绑定客户 → 开会话 → 返回语音导航播报文本与按键菜单</td></tr>
              <tr><td><code>dtmf</code></td><td>按键 → 命中菜单项：<code>bot</code> 返回播报文本并记入会话；<code>agent</code> 返回转人工指令并把会话放进待接待队列</td></tr>
              <tr><td><code>hangup</code></td><td>生成通话小结写入会话 → 自动建工单（来源渠道=电话热线）</td></tr>
            </tbody>
          </table>
        </section>

        <section id="doc-email" class="doc-sec">
          <h3>七、邮件工单</h3>
          <p>
            <span class="tag ok">已开放</span>
            客户把邮件发到<b>企业自己的客服邮箱</b>，平台自动完成
            <b>秒级回执 + 自动建工单 + 解析诉求与优先级</b>；坐席回复、结单回访都回到同一封邮件会话里。
            平台用 <b>IMAP 拉取</b>这封邮件收到工单、用 <b>SMTP</b> 把回信发回客户，
            邮箱参数按渠道配置，<b>每个租户用自己的邮箱、互不影响</b>，企业侧不需要写任何代码。
          </p>
          <h4>7.1 配置</h4>
          <ol class="doc-list">
            <li>创建「邮件工单」渠道，进「原生接入配置」页填<b>收信（IMAP）</b>：服务器 / 账号 / 授权码 / 收件文件夹。</li>
            <li>填<b>发信（SMTP）</b>：服务器 / 账号 / 授权码 / 发件人显示名——受理回执、坐席回复、结单回访都用它发。</li>
            <li>用联调模拟器发一封模拟邮件，确认工单中心出现了工单、回执也已发出。</li>
          </ol>
          <table class="doc-table">
            <thead><tr><th>环节</th><th>说明</th></tr></thead>
            <tbody>
              <tr><td>租户隔离</td><td>IMAP / SMTP 参数都保存在这条渠道的配置里，A 企业的邮箱信件只会进 A 企业</td></tr>
              <tr><td>幂等</td><td>按邮件 <code>Message-ID</code> 去重，重复拉取不会建两张工单</td></tr>
              <tr><td>受理回执</td><td>收到邮件后立刻用 SMTP 回执；模板里的 <code>{ticketNo}</code> 会替换成真实工单号</td></tr>
              <tr><td>诉求解析</td><td>退费售后 / 发票 / 物流 / 投诉 / 试听预约 / 上课问题 关键词解析出标题、意图与优先级</td></tr>
              <tr><td>SLA 与回访</td><td>建单即进入 SLA 计时；坐席回复同步发回邮箱；工单解决后自动发带评价链接的回访邮件</td></tr>
            </tbody>
          </table>
          <p class="muted">
            安全提示：IMAP / SMTP 密码代表企业收发信，请使用邮箱服务商提供的「授权码」而不是登录密码，
            并随人员变动及时轮换。
          </p>
        </section>

        <section id="doc-api" class="doc-sec">
          <h3>八、开放接口参考</h3>
          <p>
            所有接口统一前缀 <code>/api</code>（开发环境由前端 Vite 代理到网关 9090）。
            响应体统一为 <code>{ code, message, data, requestId, timestamp }</code>，<code>code = 0</code> 表示成功。
          </p>

          <h4>8.1 开会话（免登录，访客侧）</h4>
          <div class="api-line"><span class="m post">POST</span><code>/api/customer/sessions/open</code></div>
          <CodeBlock label="请求示例" :code="code.apiOpenRequest" />
          <table class="doc-table">
            <thead><tr><th>字段</th><th>类型</th><th>必填</th><th>说明</th></tr></thead>
            <tbody>
              <tr><td><code>appKey</code></td><td>string</td><td>是</td><td>渠道 AppID</td></tr>
              <tr><td><code>visitorToken</code></td><td>string</td><td>否</td><td>上次返回的身份令牌，回传即复用同一客户</td></tr>
              <tr><td><code>visitorName</code></td><td>string</td><td>否</td><td>访客显示名</td></tr>
            </tbody>
          </table>
          <CodeBlock label="响应示例" :code="code.apiOpenResponse" />
          <table class="doc-table">
            <thead><tr><th>字段</th><th>说明</th></tr></thead>
            <tbody>
              <tr><td><code>sessionNo</code></td><td>会话号（S 开头），后续接口都用它</td></tr>
              <tr><td><code>visitorToken</code></td><td><b>会话令牌</b>：连长连接用，默认 12 小时有效</td></tr>
              <tr><td><code>visitorIdentityToken</code></td><td><b>身份令牌</b>：本地保存（默认 30 天），下次打开回传以复用客户档案</td></tr>
              <tr><td><code>tokenExpireAt</code> / <code>sessionStatus</code> / <code>created</code></td><td>令牌过期时间 / 会话状态 / 是否新建了会话</td></tr>
              <tr><td><code>botName</code></td><td>机器人显示名（企业配置），界面标签用它，别写死「机器人」</td></tr>
            </tbody>
          </table>

          <h4>8.2 实时长连接（收发消息）</h4>
          <div class="api-line"><span class="m ws">WS</span><code>/ws/realtime?token=&lt;会话令牌&gt;</code></div>
          <CodeBlock label="JavaScript" :code="code.apiWs" />
          <table class="doc-table">
            <thead><tr><th>约定</th><th>说明</th></tr></thead>
            <tbody>
              <tr><td>幂等</td><td>每条消息带 <code>clientMsgNo</code>，服务端按它去重，重发不会产生两条</td></tr>
              <tr><td>时序</td><td>服务端 ACK 带 <code>seq</code>（会话内自增），前端按 seq 排序即可</td></tr>
              <tr><td>断线补偿</td><td>重连后用 <code>GET /api/customer/sessions/{sessionNo}/messages?afterSeq=上次最大seq</code> 补齐</td></tr>
              <tr><td>在线状态</td><td>访客离线会由服务端自动结束会话，避免坐席一直盯着「客户在线」</td></tr>
            </tbody>
          </table>

          <h4>8.3 满意度评价</h4>
          <div class="api-line"><span class="m post">POST</span><code>/api/customer/sessions/{sessionNo}/csat</code></div>
          <CodeBlock label="请求示例" :code="code.apiCsat" />

          <h4>8.4 图片 / 附件</h4>
          <div class="api-line"><span class="m post">POST</span><code>/api/customer/sessions/attachments</code></div>
          <p>
            访客发图走这个接口（<code>multipart/form-data</code>，用渠道 AppID 鉴权），返回文件 ID 后随消息一起发送；
            文件只保存到对象存储，服务端不落本地磁盘。
          </p>

          <h4>8.5 管理端接口（登录态）</h4>
          <p>
            服务端集成时带登录令牌：<code>Authorization: Bearer &lt;登录令牌&gt;</code>；
            租户以令牌为准，网关会校验请求头 <code>X-Tenant-Code</code> 与令牌一致。
          </p>
          <table class="doc-table">
            <thead><tr><th>用途</th><th>接口</th></tr></thead>
            <tbody>
              <tr><td>渠道列表</td><td><code>GET /api/customer/channels</code></td></tr>
              <tr><td>创建渠道</td><td><code>POST /api/customer/channels</code>（<code>channelType</code>：1 网站 / 2 公众号 / 3 小程序）</td></tr>
              <tr><td>轮换密钥</td><td><code>POST /api/customer/channels/{channelId}/key/rotate</code></td></tr>
              <tr><td>启用 / 停用</td><td><code>POST /api/customer/channels/{channelId}/status</code>（<code>{ enabled }</code>）</td></tr>
              <tr><td>绑定技能组</td><td><code>POST /api/customer/skill-groups/bind-channel</code>（<code>{ channelId, skillGroupId }</code>）</td></tr>
              <tr><td>会话列表 / 消息</td><td><code>GET /api/customer/sessions</code>、<code>GET /api/customer/sessions/{sessionNo}/messages</code></td></tr>
            </tbody>
          </table>
          <div class="callout info">
            <b>渠道密钥怎么用</b>：AppID 是公开标识，直接写在页面/菜单里即可；服务端调用管理接口时才需要登录令牌。
            如果密钥泄露，在控制台点「重新生成密钥」会立即作废旧 AppID——记得同步更新线上配置。
          </div>
        </section>

        <section id="doc-security" class="doc-sec">
          <h3>九、安全与合规</h3>
          <table class="doc-table">
            <thead><tr><th>机制</th><th>说明</th></tr></thead>
            <tbody>
              <tr><td>令牌体系</td><td>会话令牌（默认 12 小时）与访客身份令牌（默认 30 天）都由服务端签发，前端只保存不解析；坐席端用登录令牌</td></tr>
              <tr><td>渠道域名白名单</td><td>可在渠道上配置允许的来源域名；配置后来源不在名单内直接拒绝（<code>40301</code>）。来源优先取 <code>Origin</code>，取不到回退 <code>Referer</code></td></tr>
              <tr><td>开会话限流</td><td>默认按 <code>AppID + 客户端 IP</code> 每分钟 30 次，超限返回 <code>42900</code>；可通过 <code>supportflow.visitor.open-rate-limit-per-minute</code> 调整</td></tr>
              <tr><td>原生渠道回调签名</td><td>App / 400 热线 / 邮件网关的回调都要带 <code>X-SupportFlow-Timestamp</code> 与 <code>X-SupportFlow-Signature</code>：HMAC-SHA256(密钥, 时间戳 + "\n" + 请求体)，并限制 <b>5 分钟</b>时间窗口防重放；密钥字段保存后不再回显</td></tr>
              <tr><td>租户隔离</td><td>业务数据按租户隔离，数据库行级安全（RLS）兜底；跨租户请求在网关即被拒绝并记安全事件</td></tr>
              <tr><td>敏感信息</td><td>手机号等字段按角色做字段级权限，查看会写审计日志；访客侧不返回内部备注</td></tr>
              <tr><td>传输安全</td><td>生产环境全站 HTTPS / WSS；挂件只创建自己域名下的 iframe，不读取宿主页面其他内容</td></tr>
            </tbody>
          </table>
        </section>

        <section id="doc-troubleshooting" class="doc-sec">
          <h3>十、联调与排错</h3>
          <h4>10.1 联调入口</h4>
          <p>控制台「渠道接入 → 测试会话」会以<b>新访客</b>身份打开客服页，方便反复造会话；坐席工作台（在线客服）立刻能看到。</p>

          <h4>10.2 错误码</h4>
          <table class="doc-table">
            <thead><tr><th>code</th><th>含义</th><th>常见处理</th></tr></thead>
            <tbody>
              <tr><td>0</td><td>成功</td><td>—</td></tr>
              <tr><td>40000 / 40001 / 40002</td><td>参数错误 / 业务参数不合法</td><td>按 message 提示修正入参</td></tr>
              <tr><td>40100</td><td>未认证或登录已过期</td><td>重新登录或刷新令牌</td></tr>
              <tr><td>40110</td><td>缺少租户上下文</td><td>检查请求头 <code>X-Tenant-Code</code> 与令牌</td></tr>
              <tr><td>40300 / 40302</td><td>无权限 / 令牌里没有租户信息</td><td>换有权限的账号</td></tr>
              <tr><td>40301</td><td>业务侧拒绝：域名不在白名单、渠道已停用、访客身份无效</td><td>按 message 定位：改白名单 / 启用渠道 / 重新打开会话</td></tr>
              <tr><td>40400 / 40401</td><td>资源不存在 / 渠道密钥无效或已停用、会话不存在</td><td>确认 AppID、会话号是否正确</td></tr>
              <tr><td>42900</td><td>会话创建过于频繁</td><td>降低频率（默认限流 30 次/分钟）</td></tr>
              <tr><td>50000 / 50001 / 50002</td><td>服务端错误</td><td>带 <code>requestId</code> 找平台排查</td></tr>
            </tbody>
          </table>

          <h4>10.3 常见问题</h4>
          <div class="faq-list">
            <div class="faq-row">
              <div class="faq-q">原生渠道回调一直 403，提示签名校验失败？</div>
              <div class="faq-a">三个原因：① 签名算法不一致——必须是 HMAC-SHA256(密钥, 时间戳 + "\n" + <b>原始请求体</b>)，先把 body 反序列化再拼回去会失败；② 时间戳与服务器差超过 5 分钟（NTP 没同步）；③ 密钥对不上（比如改过配置但端上没更新，密钥字段留空保存表示"不修改"）。</div>
            </div>
            <div class="faq-row">
              <div class="faq-q">400 电话的语音是平台播的吗？</div>
              <div class="faq-a">不是。平台只返回「播什么」（<code>say</code>）与「做什么」（<code>action</code>），TTS 与放音由呼叫中心完成——这样你可以换任意呼叫中心，不用把电话号码和线路绑死在客服平台里。</div>
            </div>
            <div class="faq-row">
              <div class="faq-q">邮件客户收到两封回执？</div>
              <div class="faq-a">多半是邮件网关重推了同一封邮件、而 <code>Message-ID</code> 没带过来（平台只能按发件人+主题+正文做兜底去重）。请在网关里透传原始 <code>Message-ID</code>，幂等就完全准确了。</div>
            </div>
            <div v-for="item in faqs" :key="item.q" class="faq-row">
              <div class="faq-q">{{ item.q }}</div>
              <div class="faq-a">{{ item.a }}</div>
            </div>
          </div>
        </section>

        <section id="doc-checklist" class="doc-sec">
          <h3>十一、上线检查清单</h3>
          <div class="checklist">
            <label v-for="item in checklist" :key="item" class="check-row">
              <input type="checkbox" />
              <span>{{ item }}</span>
            </label>
          </div>
          <div class="callout info">
            清单全部打勾再上线；上线后在「数据概览」看渠道会话量是否正常增长，
            并在「渠道接入」确认渠道状态为<b>生效中</b>。
          </div>
        </section>
      </main>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import CodeBlock from '../../components/CodeBlock.vue'

const router = useRouter()

const sections = [
  { id: 'doc-overview', title: '一、接入总览' },
  { id: 'doc-web', title: '二、网站在线客服' },
  { id: 'doc-wechat', title: '三、微信公众号' },
  { id: 'doc-miniapp', title: '四、微信小程序' },
  { id: 'doc-app', title: '五、官方 App' },
  { id: 'doc-phone', title: '六、400 电话热线' },
  { id: 'doc-email', title: '七、邮件工单' },
  { id: 'doc-api', title: '八、开放接口参考' },
  { id: 'doc-security', title: '九、安全与合规' },
  { id: 'doc-troubleshooting', title: '十、联调与排错' },
  { id: 'doc-checklist', title: '十一、上线检查清单' },
]

const active = ref(sections[0].id)
let observer: IntersectionObserver | null = null

onMounted(() => {
  // 滚动时高亮当前章节（用 IntersectionObserver，不依赖具体在哪个容器里滚动）
  observer = new IntersectionObserver(
    (entries) => {
      const visible = entries
        .filter((entry) => entry.isIntersecting)
        .sort((a, b) => a.boundingClientRect.top - b.boundingClientRect.top)[0]
      if (visible?.target.id) {
        active.value = visible.target.id
      }
    },
    { rootMargin: '-96px 0px -70% 0px', threshold: [0, 1] },
  )
  sections.forEach((item) => {
    const el = document.getElementById(item.id)
    if (el) observer?.observe(el)
  })
})

onBeforeUnmount(() => observer?.disconnect())

function go(id: string) {
  active.value = id
  document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

/** 代码片段集中在脚本里，页面上用 CodeBlock 渲染（带复制按钮） */
const code = {
  webWidget: `<script src="https://你的客服前端域名/widget.js" data-app-id="你的AppID" async><\/script>

<!-- 需要改标题 / 主色 / 自动展开时，用完整写法 -->
<script
  src="https://你的客服前端域名/widget.js"
  data-app-id="你的AppID"
  data-host="https://你的客服前端域名"
  data-title="在线客服"
  data-color="#2563eb"
  data-auto-open="1"
  async
><\/script>`,
  visitorUrl: `https://你的客服前端域名/visitor?appId=你的AppID&name=官网访客&embed=1`,
  webIframe: `<iframe
  src="https://你的客服前端域名/visitor?appId=你的AppID&embed=1"
  title="在线客服"
  width="380"
  height="600"
  style="border:0;border-radius:14px;box-shadow:0 18px 48px rgba(15,23,42,.18)"
><\/iframe>`,
  wechatMenu: `https://你的客服前端域名/visitor?appId=你的AppID&name=微信访客`,
  wechatCallback: `https://你的网关域名/api/customer/wechat/你的公众号AppID/callback`,
  wechatOauth: `https://你的网关域名/api/customer/wechat/你的公众号AppID/oauth`,
  miniappCallback: `https://你的网关域名/api/customer/wechat/你的小程序AppID/miniapp`,
  appMessage: `# 请求头（签名：HMAC-SHA256(推送密钥, 时间戳 + "\\n" + 请求体)）
curl -X POST https://你的网关域名/api/customer/native/app/你的渠道密钥/message \\
  -H 'Content-Type: application/json' \\
  -H 'X-SupportFlow-Timestamp: 1789000000000' \\
  -H 'X-SupportFlow-Signature: 用上面的算法算出来的十六进制签名' \\
  -d '{
    "userId": "你的App用户ID",
    "userName": "张先生",
    "content": "退货多久到账？",
    "clientMsgNo": "v-1789000000000-abc12"
  }'`,
  phoneEvent: `# 呼入
curl -X POST https://你的网关域名/api/customer/native/phone/你的渠道密钥/event \\
  -H 'Content-Type: application/json' \\
  -H 'X-SupportFlow-Timestamp: 1789000000000' \\
  -H 'X-SupportFlow-Signature: 签名' \\
  -d '{ "event": "incoming", "callId": "call-20260926-0001", "from": "13800000000" }'

# 客户按键
{ "event": "dtmf", "callId": "call-20260926-0001", "digits": "1" }

# 挂机（带本次通话的诉求与处理动作，平台据此生成小结）
{ "event": "hangup", "callId": "call-20260926-0001",
  "intent": "订单查询", "action": "AI 语音播报并短信同步", "duration": "01:12" }`,
  phoneReply: `{
  "code": 0,
  "data": {
    "action": "play",
    "say": "已为您查询到最近一笔订单正在配送中，预计今天十八点前送达…",
    "menu": [ { "key": "1", "label": "订单查询", "action": "bot" } ],
    "sessionNo": "S20260926101328000"
  }
}`,
  mailInbound: `curl -X POST https://你的网关域名/api/customer/native/mail/你的渠道密钥/inbound \\
  -H 'Content-Type: application/json' \\
  -H 'X-SupportFlow-Timestamp: 1789000000000' \\
  -H 'X-SupportFlow-Signature: 签名' \\
  -d '{
    "from": "mengqi.zhang@example.com",
    "fromName": "张梦琪",
    "subject": "发票抬头修改为：杭州SupportFlow",
    "text": "你好，我们公司抬头改了，麻烦把这笔订单的发票抬头改成新抬头，税号 91330100MA2XXXXXXX。",
    "messageId": "<20260926.1018@example.com>"
  }'`,
  mailOutbound: `# 平台调用你配置的「发信接口地址」
POST https://你的邮件网关/supportflow/send
X-SupportFlow-Timestamp: 1789000000000
X-SupportFlow-Signature: 签名

{
  "to": "mengqi.zhang@example.com",
  "subject": "回执：您的邮件已受理（TK-260926-021）",
  "text": "您好，您的邮件已受理，工单号 TK-260926-021，我们会在 1 个工作日内处理并回复。",
  "inReplyTo": "<20260926.1018@example.com>"
}`,
  miniappWxml: `<!-- pages/support/index.wxml -->
<web-view src="https://你的客服前端域名/visitor?appId=你的AppID&name=小程序访客" />`,
  appAndroid: `// 客服页里放一个 WebView
val webView = findViewById<WebView>(R.id.supportWebView)
webView.settings.javaScriptEnabled = true
webView.settings.domStorageEnabled = true
webView.webViewClient = WebViewClient()
webView.loadUrl("https://你的客服前端域名/visitor?appId=你的AppID&name=App用户")`,
  appIos: `// 客服页用 WKWebView 打开
let webView = WKWebView(frame: view.bounds)
view.addSubview(webView)
if let url = URL(string: "https://你的客服前端域名/visitor?appId=你的AppID&name=App用户") {
    webView.load(URLRequest(url: url))
}`,
  apiOpenRequest: `curl -X POST https://你的网关域名/api/customer/sessions/open \\
  -H 'Content-Type: application/json' \\
  -d '{
    "appKey": "你的AppID",
    "visitorName": "张先生",
    "visitorToken": "上次返回的身份令牌（首次可不传）"
  }'`,
  apiOpenResponse: `{
  "code": 0,
  "message": "成功",
  "data": {
    "sessionNo": "S20260924101328000",
    "customerNo": "C20260924000001",
    "customerName": "张先生",
    "visitorToken": "会话令牌（默认 12 小时）",
    "visitorIdentityToken": "身份令牌（默认 30 天，本地保存）",
    "tokenExpireAt": "2026-09-24T22:13:28",
    "sessionStatus": 1,
    "created": true,
    "botName": "小云"
  }
}`,
  apiWs: `// 开会话拿到 visitorToken 后建立长连接（坐席端换成登录令牌）
const ws = new WebSocket('wss://你的实时网关域名/ws/realtime?token=' + visitorToken)

ws.onopen = () => {
  // clientMsgNo 由客户端生成，重发靠它去重
  ws.send(JSON.stringify({
    type: 'message',
    sessionNo,
    clientMsgNo: 'v-' + Date.now() + '-' + Math.random().toString(36).slice(2, 7),
    contentType: 1,
    content: '退货多久到账？'
  }))
}

ws.onmessage = (event) => {
  const payload = JSON.parse(event.data)
  // ack：服务端已落库并带 seq；message：对方消息（机器人 / 坐席）
  console.log(payload)
}`,
  apiCsat: `curl -X POST https://你的网关域名/api/customer/sessions/S20260924101328000/csat \\
  -H 'Content-Type: application/json' \\
  -d '{ "score": 5, "feedback": "响应很快，问题解决了", "visitorToken": "会话令牌" }'`,
}

const faqs = [
  {
    q: '页面右下角没有出现客服按钮？',
    a: '先看浏览器控制台：CSP 拦了 script-src、被广告拦截插件拦掉、或脚本放在 </body> 之前但页面没加载完。用无痕窗口排除插件，再把客服域名加进 CSP 的 script-src。',
  },
  {
    q: '提示「当前域名未在渠道白名单内」怎么办？',
    a: '该渠道开启了域名白名单（错误码 40301）。请在渠道配置里把来源域名加进去，并确认与访问地址完全一致（协议、域名、端口都要对）。',
  },
  {
    q: '提示「会话创建过于频繁」？',
    a: '触发了开会话限流：默认同一 AppID + 同一 IP 每分钟 30 次（42900）。降低刷新频率，或让平台调大 supportflow.visitor.open-rate-limit-per-minute。',
  },
  {
    q: '客户发消息后没回复，长连接一直重连？',
    a: '会话令牌默认 12 小时有效，过期后握手会被拒绝。让前端重新调用 /api/customer/sessions/open 拿新令牌（带上身份令牌即可复用原会话）。',
  },
  {
    q: '同一个客户被识别成新客户？',
    a: '访客身份存在浏览器本地：换浏览器、清缓存、无痕窗口都会变成新客户。联调时可以用 fresh=1 主动造新客户。',
  },
  {
    q: '坐席工作台里看不到这条会话？',
    a: '依次确认：渠道状态是否「生效中」、渠道是否绑定了技能组且组内有在线坐席、这条会话是否已被其他坐席接走（去「全部会话」看）。',
  },
  {
    q: '微信里点开是空白 / 打不开？',
    a: '微信内要求 HTTPS，且域名不能是内网地址或自签名证书；公众号菜单里必须是完整的 https 链接。',
  },
]

const checklist = [
  '企业已开通并通过审核，生产域名已备案且为 HTTPS',
  '渠道 AppID 已拿到，并替换到线上代码 / 菜单配置（不是测试值）',
  '如启用了渠道域名白名单，来源域名已配置',
  '渠道状态为「生效中」，并已绑定技能组',
  '技能组内至少一名坐席在线，且坐席处于「在线」状态',
  '用「测试会话」跑通完整链路：机器人接待 → 转人工 → 满意度评价',
  '图片收发与断线重连各验证一次（弱网下刷新页面即可）',
  '上线后清理测试会话，确认数据概览里的渠道会话量正常增长',
]
</script>

<style scoped>
.docs-page { width: 100%; }
.doc-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; flex-wrap: wrap; margin-bottom: 18px; }
.doc-title { margin: 0; font-size: 20px; color: #0f172a; }
.doc-sub { margin: 6px 0 0; font-size: 13px; color: #64748b; }
.doc-ver { margin-left: 8px; font-size: 12px; color: #94a3b8; }
.doc-head-actions { display: flex; gap: 10px; flex-wrap: wrap; }

.doc-body { display: grid; grid-template-columns: 216px 1fr; gap: 22px; align-items: start; }
.doc-nav { position: sticky; top: 12px; border: 1px solid #e6e8ef; border-radius: 12px; background: #fff; padding: 14px 12px; }
.dn-title { font-size: 12.5px; font-weight: 700; color: #0f172a; padding: 0 8px 8px; }
.doc-nav a { display: block; font-size: 12.5px; color: #475569; text-decoration: none; padding: 7px 10px; border-radius: 8px; line-height: 1.5; }
.doc-nav a:hover { background: #f1f5f9; color: #2563eb; }
.doc-nav a.on { background: #eff6ff; color: #2563eb; font-weight: 600; }
.dn-tip { margin-top: 10px; padding: 10px; border-top: 1px dashed #e6e8ef; font-size: 11.5px; line-height: 1.7; color: #94a3b8; }

.doc-main { min-width: 0; }
.doc-sec { background: #fff; border: 1px solid #e6e8ef; border-radius: 14px; padding: 22px 26px; margin-bottom: 16px; scroll-margin-top: 12px; }
.doc-sec h3 { margin: 0 0 14px; font-size: 17px; color: #0f172a; }
.doc-sec h4 { margin: 22px 0 10px; font-size: 14.5px; color: #0f172a; }
.doc-sec p { margin: 8px 0; font-size: 13.5px; line-height: 1.9; color: #334155; }
.doc-sec code { background: #f1f5f9; border: 1px solid #e2e8f0; border-radius: 5px; padding: 1px 5px; font-size: 12.5px; color: #0f172a; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
.doc-sec .note { background: #f8fafc; border-left: 3px solid #cbd5e1; padding: 10px 12px; border-radius: 0 8px 8px 0; font-size: 12.5px; color: #475569; }

.steps { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; margin: 16px 0 6px; }
.step { display: flex; gap: 10px; border: 1px solid #e6e8ef; border-radius: 12px; padding: 14px; background: #fafbfc; }
.step .n { width: 24px; height: 24px; border-radius: 8px; background: #eff6ff; color: #2563eb; font-weight: 700; font-size: 12.5px; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.step b { font-size: 13px; color: #0f172a; }
.step p { margin: 4px 0 0; font-size: 12px; line-height: 1.7; color: #64748b; }

.doc-table { width: 100%; border-collapse: collapse; margin: 12px 0; font-size: 13px; }
.doc-table th, .doc-table td { border: 1px solid #e6e8ef; padding: 9px 12px; text-align: left; vertical-align: top; line-height: 1.75; }
.doc-table th { background: #f8fafc; color: #0f172a; font-weight: 600; font-size: 12.5px; }
.doc-table td { color: #334155; }

.doc-list { margin: 10px 0; padding-left: 20px; }
.doc-list li { font-size: 13.5px; line-height: 2; color: #334155; }

.tag { display: inline-block; border-radius: 999px; padding: 2px 9px; font-size: 11.5px; font-weight: 600; white-space: nowrap; }
.tag.ok { background: #e7f8f0; color: #059669; }
.tag.warn { background: #fef4e6; color: #b45309; }
.tag.plan { background: #f1f5f9; color: #64748b; }

.callout { margin: 14px 0; border-radius: 10px; padding: 12px 14px; font-size: 13px; line-height: 1.85; color: #334155; }
.callout b { color: #0f172a; }
.callout.info { background: #f8fbff; border: 1px solid #dbeafe; }
.callout.warn { background: #fffbeb; border: 1px solid #fde68a; }
.callout.plan { background: #f8fafc; border: 1px dashed #cbd5e1; }

.api-line { display: flex; align-items: center; gap: 10px; margin: 10px 0; font-size: 13px; }
.api-line .m { border-radius: 6px; padding: 2px 8px; font-size: 11.5px; font-weight: 700; color: #fff; }
.api-line .m.post { background: #10b981; }
.api-line .m.ws { background: #7c3aed; }
.api-line code { background: #f8fafc; }

.faq-list { display: grid; gap: 10px; margin-top: 10px; }
.faq-row { border: 1px solid #e6e8ef; border-radius: 10px; padding: 12px 14px; background: #fafbfc; }
.faq-q { font-size: 13px; font-weight: 600; color: #0f172a; }
.faq-a { margin-top: 6px; font-size: 12.5px; line-height: 1.85; color: #475569; }

.checklist { display: grid; gap: 8px; margin: 12px 0; }
.check-row { display: flex; align-items: flex-start; gap: 10px; font-size: 13px; color: #334155; line-height: 1.8; cursor: pointer; }
.check-row input { margin-top: 4px; accent-color: #2563eb; }

@media (max-width: 1080px) {
  .doc-body { grid-template-columns: 1fr; }
  .doc-nav { position: static; display: flex; flex-wrap: wrap; gap: 6px; }
  .dn-title, .dn-tip { width: 100%; }
  .steps { grid-template-columns: repeat(2, 1fr); }
}
@media (max-width: 720px) {
  .steps { grid-template-columns: 1fr; }
  .doc-sec { padding: 18px; }
}
</style>
