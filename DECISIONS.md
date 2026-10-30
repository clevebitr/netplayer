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
