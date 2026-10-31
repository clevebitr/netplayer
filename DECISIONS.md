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

## 2026-10-30 — 1.21.11 自绘文字 alpha=0 不显示等 UI 坑

1.21.11 的 GuiGraphics.drawString 内部是 `if (ARGB.alpha(color) != 0) submitText(...)`，直接用 0xRRGGBB（alpha=0）写颜色会**完全不渲染**，而且没有任何报错/警告。症状很像"UI 没标题/没提示"，但按钮(自带 ARGB)正常。以后所有自绘颜色一律 0xFFRRGGBB；改完 UI 若发现只有控件文字、没有自己画的文字，先查 alpha。

同时记下几个同批踩到的坑：
- 暂停/标题菜单插行要用原版 GridLayout 真实尺寸：单元格 106、按钮 98、整行 204（4px padding），否则右侧那列会偏 4px。
- 自定义域名 + Workers Route 必须另有代理开启的 DNS 记录，Route 不会创建记录；netplayer.clevebitr.dpdns.org 当时是 NXDOMAIN。
- 游戏 JVM 不吃 Windows 系统代理，wss://*.workers.dev 直连超时；已在 config 与设置界面加 proxyHost/proxyPort，WSS 与 Mojang 校验都走它。

## 2026-10-31 — M3 安全修复：身份闭环（id↔公钥、签名覆盖、授权双检）

审计结论：加密原语没问题，问题全在身份绑定。根因是两台信令服务器的 hello 只验签自带的 ed 而不检查 `id == sha256(ed)[:16]`，客户端又用消息里自带的公钥验 ICE（自签），于是「知道某人 id」≈「能冒充某人」；再叠加 presence 广播 room.id + allow 白名单（watch 不限好友关系），白名单等于形同虚设。

改动（0.2.0，协议不兼容 0.1.x）：
1. core/Profile.selfCertified()（id 必须是 ed 的 sha256 前 16 hex）；Crypto.verify 在验签前直接拒绝非自证 profile —— 单点堵死「id=受害者 + 自己的密钥」。两台服务器 hello 各自显式检查并回 `id does not match key`。
2. core/Handshake：签名基串集中定义。ICE 用 `ice2:room:from:to:ufrag:pwd:epk`（绑双方身份+房间+临时公钥），premium 用 `premium1:name:uuid:serverId`。客户端 IceState 记住开工时的对端 Profile，processIce 只用它验签，且要求 from.id == state.peer.id、room 与 state.roomId 一致；earlyIce 消费时同样走这条路，所以抢跑不再能劫持隧道。
3. 直连码升 v2：签名覆盖「除 sig 外的全部字段」的规范化 JSON（Json.canonical 排序键），所以 allow/premium/world/token 都签进去了；顺手修掉 generateDirectOffer 里 premium 被写两次。房主侧 applyDirectAnswer 改为用自己 hosting 房间的 allow/token 复核（并让 guest 在 answer 里回传 token）。
4. 授权双检：join_req 由服务端把 guest 出示的 token 一并转发，房主用自己 room 的 allow/token 再判一次，不再盲信中继。
5. 服务端不再下发 room.id/token/allow：presence 只给 world + premium + open（按接收者算）。客户端 join 改为「报 host 名」，由服务端解析该 host 当前房间；npjoin1 房间密钥仍带 roomId 走显式路径。顺带 roomOpen 时清掉同一 host 的旧房间。
6. 只有房主能 join_ans（且只对 waiting 里的 guest）与 room_close：否则任何知道 room id 的会话都能替房主 accept 并把 token 骗到手。
7. 好友请求改为人工确认：add 只进 addRequests（UI 里 [同意]/[拒绝]），不再静默写 friends.json。
8. 正版证明用本机 Ed25519 签名，host 核对 hasJoined 的 uuid == proof.uuid，并按 friend id 首次钉住 mcUuid（TOFU），换号会被拒；presence 里的 mcUuid 不再写回 friends.json（那是未认证数据）。
9. P2 硬化：ReliableStream 接收缓冲上限 256KiB + 越界 ACK 拒绝；ICE 候选只接受 IP 字面量（拒绝主机名/0.0.0.0/组播）且上限 32 个；服务端行长度 64KiB、watch 512 个 id、待处理好友请求 64 条、单会话 ICE 256 条、在线会话 4096；identity.json 写后尽力 chmod 600；客户端请求队列上限 8。

回归：:core:test（9）、:server:test（5，新增 socket 级协议测试）、worker typecheck + smoke（新增 6 条安全断言）、:mod:compileJava 与 ./gradlew build 全绿；Java 客户端 ↔ Worker 的真实 WS 握手仍通过。
不做：TURN 中继、多人同时进房、皮肤备注。

## 2026-10-31 — M3 功能：多人同时进房 + 房主连接面板 + 备注/头像修正

多人进房本来在架构上就是通的（tunnels/icePeers 都按好友 id 分表，每客一条 Tunnel + 每客一次 publishServer LAN 连接），真正缺的是上限、回收、可观测性和 UI 假限制：

1. 房主侧：guests() 返回在线隧道（名字/链路/RTT），kickGuest() 关隧道+关 ICE+发 kick；MAX_GUESTS=8；join_req 时若未开放/房间不匹配直接回 room_closed、已在隧道里的重复请求忽略、满员回 room_full（服务端不参与，房主自己判）。
2. kick 走与 ice 相同的中继（服务端一行 case，Java/TS 皆是），因为 relayIce 保留原消息类型。客端 onKick 要求 from 自证身份 == joinedHostId 且 room == joinedRoomId，然后 leaveWorld() 在客户端线程 disconnect 回主菜单。加 joinedRoomId 的目的就是防止旧世界的 kick 误伤新会话。
3. 死隧道回收：Tunnel.isOpen()（closed && !stream.isOpen()），tick 里 reapTunnels() 把 end 掉的隧道从 tunnels/icePeers 里摘掉——之前客端直接退出世界后房主侧会一直显示"已连接"。
4. UI：好友列表不再硬性只显示 2 条加入请求（改为按可用行数）；对好友开放界面在房主态显示「已连接的好友」两行 + [断开]，布局重排避免与正版状态行/底部按钮重叠。
5. 备注/头像：pinMcAccount 之前存的是 dashless uuid，Heads 里 UUID.fromString 直接抛异常导致正版好友永远没头像。现在持久化带连字符的原样 uuid（比较一律 dashless），Heads 也兼容 32 位裸 hex。setNote 清洗控制字符并限 48 字符。

回归：:core:test 9、:server:test 5、worker smoke 全绿（新增 kick 中继断言），./gradlew build 通过。
待办：TURN 中继（ice4j 对 RFC8656 支持很弱，需要自写 allocate/channel-data 客户端，或改用自建 coturn + 自定义 harvester），这一条要单独立里程碑。

## 2026-10-31 — M4 TURN 中继：自写 RFC5766 客户端 + 回退路径

ice4j 开源版没有 TURN，所以中继只能自己写。范围刻意压到「一条字节流」：core/TurnClient（RFC5766 UDP + 长期凭证）+ mod/net/TurnRelay（实现新抽出的 Link 接口，Tunnel 不再只认 IceLink）。

实现要点
- StunCodec：20 字节头 + TLV 属性，XOR-ADDRESS、ERROR-CODE、LIFETIME、REQUESTED-TRANSPORT、CHANNEL-NUMBER；MESSAGE-INTEGRITY = HMAC-SHA1，密钥 md5(user:realm:pass)，长度字段先把 24 字节算进去（RFC5389 §15.4）。客户端对**成功响应**也校验 MI，防止离线伪造 relayed 地址；错误响应不校验，因为服务器拒绝我们的凭证时用的是它那边的密钥。
- TurnClient：单独 reader 虚拟线程按 transaction id 匹配响应；ChannelData 与 Data indication 都往 inbox 塞；分配成功后按 granted lifetime 的一半自动 Refresh。
- 时序：allocate 与 ICE 并行跑（不牺牲直连），拿到 relayed 地址后发一条独立的签名消息 `relay1:room:from:to:addr`（`Handshake.relay`）。不能塞进 ICE 描述，因为分配通常晚于 ICE 描述；也不能不签，地址是攻击者可以拿来引流的东西。接收侧要求 from 自证身份 == state.peer.id、room 匹配、签名有效、地址必须是 IP 字面量（`Addresses.parse`，拒绝主机名 → 不给对端做 DNS/内网探测的机会）。
- 回退：iceFailed 时若两侧都拿到 relayed 地址且本地有分配 → TurnRelay.start（CreatePermission + ChannelBind 0x4001）+ 复用同一个临时 X25519 密钥（peerEpk 从已验签的 ICE 描述里记下来）→ 同一个 Tunnel/ReliableStream/AES-GCM。任一环节缺失就不硬撑，仍给原来的失败提示。
- 服务端只多认一个消息类型 `relay`（Java/TS 各一行，和 ice/kick 同一条中继路径）。

公共 TURN 调研（本机实测，CN 网络）：openrelay.metered.ca 解析失败；a.relay.metered.ca 能应答 401 挑战但用公开的 openrelayproject 凭证被 400 拒（那是 Metered 账户专用主机）；eu-0.turn.peerjs.com 解析失败；free.expressturn.com 无应答。结论：公共共享中继不适合当默认值——不稳定、常被墙、凭证公开等于谁都能蹭。所以 turnServers 默认空，设置页给「测试中继」按钮让人按自己网络实测，README 推荐自建 coturn 或 Cloudflare Realtime TURN（1TB/月免费，需 API 换临时凭证）。

回归：:core:test 16 项（含 mock TURN 的双向 1200B 收发、401 挑战、错误凭证）、:server:test 5 项、worker typecheck + smoke（新增 relay 中继断言）、./gradlew build 通过。未做：TURN over TCP / turns:（TLS），全 UDP 被封的网络仍然无解。

## 2026-10-31 — v1.0.0 发布：CI 支持正式版 tag

发布流程：main 每次 push 仍打 `v1.0-beta-<sha>` 预发布（方便随时下载最新提交），另加 `tags: ['v*']` 触发器——从 tag 构建时版本号取 tag 名、prerelease=false，产物命名 `netplayer-<ver>.jar` / `netplayer-server-<ver>.jar`。mod_version 提到 1.0.0（`-Pmod_version` 仍可被 CI 覆盖）。

验证：本地 `./gradlew build -Pmod_version=1.0.0` 通过；push tag 后两个 workflow 都绿，`gh release view v1.0.0` 显示 isPrerelease=false、Latest，两个 jar 已上传（mod 12004226 B、server 370609 B）。

注意：1.0 与 0.1.x 客户端/信令不互通（presence 载荷与签名基串都变了），线上 worker 必须先 `wrangler deploy` 再发客户端。

## 2026-10-31 — 1.0.2：Android 上 ice4j 配置加载失败（TCCL）

Android（Zalith/Pojav）实测报"连不上"，日志决定性一行：`ICE self test failed / Key 'org.ice4j.ice.CONSENT_FRESHNESS_INTERVAL' not found in source 'typesafe config'`，栈顶是 `AgentConfig.getConsentFreshnessInterval → Agent$StunKeepAliveRunner → Agent.<init> → IceLink.<init>`。也就是说 `new Agent()` 直接抛异常，ICE 根本没开始 —— 与 IPv6、防火墙无关（他们的两台机器 IPv6 前缀同 /64 只是巧合，不是原因）。

根因：ice4j 的默认值来自 `reference.conf`，Typesafe Config 通过**线程上下文类加载器**找这个资源。桌面端 TCCL 是 FML 的 module loader，能看到 mod jar；Android 启动器的 TCCL 看不到，`ConfigFactory.load()` 拿到空配置，Agent 读第一个默认值就炸。

修法：`IceLink` 构造期间把 TCCL 固定成自己的 classloader（覆盖 agent + harvester + component 整段），失败时再反射调 `JitsiConfig.Companion.reloadNewConfig()` 清掉已缓存的坏配置并重试一次。复现方式：用 child-first URLClassLoader 加载 mod jar、父加载器为 platform、TCCL 设成看不见 jar 的 system loader —— 修前抛出同样的 metaconfig 异常，修后正常构造。正常 classpath 下 ICE 仍 114ms 通过（4 个候选，全局 IPv6）。

顺带：ICE 启动失败现在会 LOG.error 打完整异常（以前只有"ICE failed before the agent started"这种没法诊断的日志）；正版证明路径改为 catch Throwable，Android JVM 若没有 java.net.http 也只会降级成"无法证明账号"而不是让加入流程崩。

发布 v1.0.2。给用户的排查顺序也写进 README：网络自检 → 日志 ICE start/failed 与两侧候选 → Windows 防火墙入站 UDP → ping -6 → CPE IPv6 防火墙 → TURN。
