# DECISIONS

> 决策与踩坑日志，只追加，不参与日常上下文。可用 grep 检索。条目按文件顺序编号（第一个 `##` 是 #1）。

## 2026-10-30 — 协议与传输设计

信令走 TCP + 换行分隔 JSON，不引 Netty：server 用 ServerSocket + 虚拟线程，客户端 core/SignalClient 一个虚拟线程读。少一套依赖、少一层 handshake，协议文档见 server/SignalServer.java。WebSocket 以后想加只需换传输。

好友关系纯本地（gameDir/netplayer/friends.json），服务端只转发 presence/好友请求（离线队列 24h，内存）。所以服务端无数据库、可随时重启。

邀请密钥两种：npfriend1.* 内含 Ed25519+X25519 公钥+昵称（TOFU 信任），npjoin1.* 内含 roomId+token（token 可绕过 allow 白名单，作为一次性进入凭证）。都有 8 位 sha256 截断校验位。

隧道 = ice4j Agent（host + STUN srflx，一次性交换候选，不做 trickle）+ 自写 ReliableStream（11 字节头、窗口 32 包、1024B payload、250ms 重传、累积 ACK）+ AES-GCM（每包随机 12B nonce，封装内 nonce 前缀）。ICE 完成后 component.send/getSocket 收发。
握手：每边临时 X25519，公钥放 ICE 消息里并用身份 Ed25519 签名 "ice:room:ufrag:pwd:epk"，所以隧道密钥前向安全且绑身份。

主机关闭世界：IntegratedServer.publishServer(GameType, cheats, port) 主动开 LAN（端口自己挑空闲的），guest 侧本地起 ServerSocket(127.0.0.1:0)，再用 ConnectScreen.startConnecting 连过去；离开当前世界用 mc.disconnect(new TitleScreen(), false)（1.21.11 的两参版本等价 disconnect(screen,false,true)，会置空 integratedServer）。

## 2026-10-30 — 1.21.11 API 与构建踩坑

1.21.11 破坏性变更：ResourceLocation -> net.minecraft.resources.Identifier；输入事件改成 KeyEvent/MouseButtonEvent/CharacterEvent record；KeyMapping 需要 KeyMapping.Category（用 Category.register(Identifier) 或在 RegisterKeyMappingsEvent 里注册）；Toast 走 Minecraft.getToastManager() + SystemToast.add(manager, new SystemToastId(ms), title, sub)；Screen.removeWidget 是 protected（子类内可用，同时清 renderables/narratables/children）。

构建：Gradle 8.14.5（腾讯镜像 dist）+ ModDevGradle 2.0.148 + NeoForge 21.11.48，Java toolchain 21。本机 maven.neoforged.net 被墙，靠 ~/.gradle/gradle.properties 里的 systemProp.* 指向 127.0.0.1:7890 代理。jar 用 jar 任务手动 fat-jar（duplicatesStrategy=EXCLUDE），不用 shadow 插件；:core 用 gson compileOnly，:server 自带 gson。

依赖体积：ice4j 会拉 jitsi-utils/jicoco-config/kotlin-stdlib/JNA/Jackson，fat jar 约 12MB。不裁剪的原因是 AgentConfig（Kotlin）在 Agent 构造时就会初始化。

## 2026-10-30 — 回环 IPv4 坑 + 无信令直连模式

1) 进不去世界的真因：Tunnel.guest() 用 InetAddress.getLoopbackAddress() 绑 ServerSocket，Windows/JDK21 下拿到的是 ::1，而 ConnectScreen 连的是字面量 127.0.0.1 → Connection refused（日志里能看到 netty AnnotatedConnectException）。改成显式 127.0.0.1，主机侧连 LAN 端口同样用 127.0.0.1。以后凡是本地回环都用字面量，不依赖 getLoopbackAddress()。

2) 无信令（纯 P2P 信令）模式：房主在自己已 publishServer 的世界上生成 npair1 直连码（内含 host profile/room/world/port/allow + ice{ufrag,pwd,cands} + 临时 X25519 公钥 + Ed25519 签名 "direct-offer:room:ufrag:pwd:epk"），通过任意聊天发给好友；好友粘贴后生成 npans1 应答码（同样的签名格式 "direct-answer:..."），房主再粘回应答码。两侧复用同一套 IceLink/Tunnel/JoinFlow：房主不 controlling、好友 controlling，好友 ICE 通过后开 127.0.0.1 端口并走既有 progressJoin 连进世界。码长约 1.6KB（base64url + 8 位校验）。一次性：重新生成会让旧应答码失效（room 不匹配 → direct_stale）。白名单仍生效。

3) 直连测试脚本验证：offer/answer 码往返 + 1.5s 人为延迟（模拟粘贴时间）+ ICE 完成 + 128KiB 回显 57ms，说明"房主后粘贴应答码"这条时序 OK（ice4j 会把先到的检查放进 preDiscoveredPairsQueue）。

## 2026-10-30 — 正版验证的实现取舍

正版验证没有去改 IntegratedServer 的 online-mode（改了会让主机自己的本地连接也要走加密握手，LAN 会直接不可用），而是在 mod 层做"进隧道前的准入检查"，用的正是原版 online-mode 服务器的两个 session 接口：
- 客端 sessionserver /session/minecraft/join（accessToken + selectedProfile + 随机 serverId）
- 主机 sessionserver /session/minecraft/hasJoined?username=&serverId= → 200 带 profile 才算通过
证明只证明"账号归属"，不改变游戏内身份（LAN 仍是 offline UUID），所以不会破皮肤/名字逻辑。

证明随 join_req（信令）或 npans1 应答码（直连）一起走，并被各自的 Ed25519 身份签名覆盖（direct-answer 签名串里已含 room/ufrag/pwd/epk；信令路径靠服务器 hello 的签名身份绑定）。room 的 premium 标志进 presence，客端据此决定要不要先生成证明；房主在点「同意」时才 hasJoined（避免证明过早过期）。

Config 默认 verifyPremium=true，OpenWorldScreen 里有开关且写入 config.json。开发/离线账号 token 是 "0"，localPremiumCapable()=false → 客端直接给出「本机账号无法通过正版验证」而不是静默失败；房主界面上也常驻一行橙色提示。sessionserver.mojang.com 本机可达（hasJoined 返回 204）。

## 2026-10-30 — WebSocket 信令 + Workers/DO 免费部署

约束：Cloudflare 免费层没有入站裸 TCP（公网 TCP 要 Spectrum，付费/合同），只有入站 WebSocket。所以新增 signal-worker/（TS，Workers + Durable Object），协议与 server/ 的 Java TCP 版**完全同构**：{"t":..,"d":..}，只有分帧变了（WS 一帧一条，读侧仍按 \n 切）。客户端 core/SignalClient 现在两条传输并存：connect(host,port) 走 TCP、connectUrl(ws://|wss://) 走 JDK 自带 java.net.http.WebSocket（发送走单独虚拟线程串行 sendText，避免 overlapping send 的 IllegalStateException）；mod/Config 加 signalingUrl，非空优先。

DO 实现要点：单实例 idFromName("global") 模拟 Java 的 synchronized 全局锁；Hibernation WS API（ctx.acceptWebSocket + serializeAttachment 存 id/state/detail/roomId）让空闲不结算 duration；watchers/rooms/pending/tickets 写进 DO storage 单键 "state"（内存缓存 + 写穿）。免费额度：Worker 请求 100k/天、DO 请求 100k/天、duration 13k GB-s/天；WS 升级算 1 次请求，帧算 DO 请求，好友级别用量差好几个数量级。免费层 DO 必须 SQLite 后端 → migrations 用 new_sqlite_classes。Ed25519 用 crypto.subtle（workerd 已实现 secure curves），profile.ed 是 X.509 SPKI，直接 importKey("spki")，失败再退 raw 32 字节。

顺手修掉 Java 版的真 bug：disconnect() 先 watchers.remove(session.id) 再 subscribersOf(session.id)，于是「好友离线」永远不广播；同时把 watchers 订阅表保留跨断线（订阅的是人不是连接），这样 hello() 里那段推送 presence 才不是死代码。两版现在一致。TS 额外加 ping→pong（不认的客户端会 ignore）。

验证：signal-worker npm run typecheck 通过；wrangler dev 下 node test/smoke.mjs 全绿（伪造 hello 被拒、双向 presence、在线/离线好友请求、房间开/关、join_req/ans、ICE 转发去 to、白名单 vs 房间令牌）；./gradlew :core:test -Pnetplayer.signal.url=ws://127.0.0.1:8787/ 跑通 Java 客户端到 Worker 的真实握手；:mod:compileJava 通过。注意 npm 11 会拦 esbuild/workerd 的 postinstall（warning 而已，wrangler dev 正常跑）。

其它免费托管候选（没选，留档）：Oracle Always Free ARM 4C/24G 及 GCP e2-micro 能跑现有 TCP jar 且零改动，但 Oracle 有闲置回收（本服务负载极低正好会被判闲置）；Fly.io 2024-10 起取消免费；Render/Railway/Koyeb/Zeabur 只暴露 HTTP(S)，同样逼你上 WS。TURN 方面 Cloudflare Realtime TURN 有 1TB/月免费额度但需要 API 换凭证且是 RFC8656 标准 TURN，ice4j 支持很弱，下一里程碑更可能是自建 coturn。
