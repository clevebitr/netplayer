# 测试指引

三层测试，从便宜到贵。**第一层就能验证 90% 的业务逻辑**（好友、密钥、审批、切世界、隧道、UI），
只验证不到 NAT 穿透本身。

## 0. 准备

```bash
# 1) 信令服务器（保持运行）
java -jar server/build/libs/netplayer-server.jar --port 25566

# 2) 两个开发客户端（两个窗口、两个账号、两个存档目录）
./gradlew :mod:runClient      # 玩家名 Dev    游戏目录 mod/run
./gradlew :mod:runClient2     # 玩家名 Friend 游戏目录 mod/run2
```

两个客户端用不同的游戏目录，因此有**各自独立的身份**（`mod/run/netplayer/identity.json` 与
`mod/run2/netplayer/identity.json`），可以互加好友。信令地址默认就是 `127.0.0.1:25566`。

## 0.5 用 Workers 免费信令（wss://）代替本机 TCP

想验证线上那种部署（信令走 WebSocket、TLS、443）时用这条，业务逻辑与第 1 节完全一致。

```bash
cd signal-worker
npm install
npm run dev            # 本地 wrangler，监听 ws://127.0.0.1:8787/
npm run smoke          # 协议端到端自检（伪造签名/离线队列/房间令牌都会跑到）
```

然后给两个开发客户端加上 `signalingUrl`（`mod/run/netplayer/config.json` 与
`mod/run2/netplayer/config.json`）：

```json
{ "signalingUrl": "ws://127.0.0.1:8787/" }
```

`signalingUrl` 非空时优先，端口和 host 被忽略；清空就退回 TCP。控制台应出现
`connected to signaling server ws://127.0.0.1:8787/`，之后按第 1 节表格逐条走。

已部署的线上地址同理，把值换成 `wss://netplayer-signal.<你的>.workers.dev/`，
再确认 `curl https://…/` 返回 `"ok": true`。

Java 客户端到 ws 服务的连通性可以单独测，不用起游戏：

```bash
./gradlew :core:test -Pnetplayer.signal.url=ws://127.0.0.1:8787/
```

## 1. 单机双开（必做，覆盖业务流）

| # | 操作 | 期望 |
| --- | --- | --- |
| 1 | 两个窗口都启动后看控制台 | 每个窗口出现 `connected to signaling server` 与 `signaling handshake done as Dev/Friend` |
| 2 | A 点主菜单左下 **好友** → **添加好友** → **复制我的密钥** | 剪贴板里得到 `npfriend1....` |
| 3 | B 点 **好友** → **添加好友**，粘贴 → **添加** | B 列表出现 Dev；A 右上角弹 toast `Dev 把你加为了好友`（实际是 B 加了 A，A 反向收到并自动互为好友） |
| 3.5 | A 的 **对好友开放** 界面里看「正版验证」开关 | 默认是**开**；两个开发窗口都是离线账号，测试时先把它**关掉**，否则好友会被拒 |
| 4 | A 进单人世界（随便新建）→ `Esc` → 左下 **对好友开放** → 全部好友：开 → **开放世界** | toast `世界已对好友开放`；B 的好友列表里 Dev 置顶，状态 `开放中 xxx（对你开放）` |
| 5 | B 点 Dev 那一行 | toast `已发送加入申请`；A 弹 toast `Friend 请求加入你的世界` |
| 6 | A 打开好友列表（`Esc` → **好友** 或 `F9`） | 列表顶部出现 `Friend 请求加入 [同意] [拒绝]` |
| 7 | A 点 **同意** | B 自动断开当前世界并进入 A 的世界 |
| 8 | B 世界内走动 / 挖方块 / 聊天 | A 端同步正常，无卡顿（本机回环） |
| 9 | A：`Esc` → **对好友开放** → **关闭开放** | B 掉线回主菜单 |

再验一遍"在单人世界里被拉走"：B 先开自己的单人世界 → 按 `F9` → 点 A → A 同意 →
B 应该**先退出自己的世界**再连进 A 的世界。

### 一次性房间密钥

A 在 **对好友开放** 里点 **复制房间密钥**（`npjoin1....`）发给 B，B 在 **添加好友** 里粘贴 →
自动发起申请（token 可绕过 A 的好友白名单，用于临时访客）。A 仍需点同意。

### 反向用例

| 场景 | 期望 |
| --- | --- |
| A 没开放世界时点好友 | 行按钮灰色不可点 |
| A 关闭开放后 B 再点 | toast `该好友没有对你开放世界` |
| B 拒绝 / A 不同意 | 各自 toast，不进入 |
| 关掉信令服务器 | 好友列表底部红字 `未连接到信令服务器`，8 秒后自动重连 |
| A 直接退出世界 | 房间自动关闭，B 端列表状态回到离线/在线 |

## 2. 双机同一局域网（验证 host candidate 直连）

1. `./gradlew build`，把 `mod/build/libs/netplayer.jar` 装到两台机器的 `mods/`。
2. 信令服务器可以继续跑在其中一台机器的 25566，另一台的 `config.json` 填该机内网 IP。
3. 按第 1 节的流程走一遍，但这次是跨进程/跨机器：
   - 日志里 `ICE connected with ... (relayed=false)`；
   - `iceDebug=true` 时能看到选中的候选对是两个内网地址。
4. **Windows 防火墙**首次会弹"允许网络访问"，必须允许（入站 UDP），否则打洞必失败。

## 3. 跨网络（验证 STUN 打洞 / IPv6）

| 组合 | 该走哪条路 | 预期 |
| --- | --- | --- |
| 两台都在 IPv6 GUA 下（`240e:` / `2001:` 开头） | host candidate (IPv6) | 成功，延迟最低 |
| 一方普通家用 NAT，另一方另一网络 | srflx + 打洞 | 多数成功 |
| 双方都在对称 NAT / 运营商 CGNAT（手机热点常见） | 无路可走 | 日志 `ICE failed, no direct path` + toast `无法直连，请尝试 IPv6 或端口转发`（v1 没有 TURN，这是预期行为） |

建议组合：A 用家里 WiFi，B 用手机热点（不同 NAT）。

## 4. 观测手段

| 位置 | 内容 |
| --- | --- |
| `mod/run/logs/latest.log` / `mod/run2/logs/latest.log` | `connected to signaling server`、`signaling handshake done`、`join request from`、`ICE start as host/guest`、`ICE connected with`、`ICE failed` |
| `netplayer/config.json` 里 `"iceDebug": true` | 打开 ice4j 的 INFO 日志，能看到候选对、nomination、选中路径 |
| 信令服务器 stdout | `Dev (...) connected from`、`room ... closed with its host` |
| `mod/run/netplayer/` | `identity.json`（私钥，勿外传）、`friends.json`、`config.json` |

## 5. 已知会失败 / 不覆盖

- 对称 NAT、CGNAT：无 TURN，必失败（v1 设计如此）。
- 企业防火墙只放行 443：UDP 出不去，失败。
- 两个客户端都用同一个游戏目录：身份相同，互加好友会失败（会提示"这是你自己的密钥"）。
- 传输层固定窗口 32 包、无拥塞控制：Minecraft 流量够用，不适合大文件。

## 6. 回归脚本（可选，无 GUI）

`core` 有 6 个单测覆盖密钥/签名/AES-GCM/协议编码：

```bash
./gradlew :core:test
```

## 7. 无信令模式（纯 P2P 信令，不需要服务器）

信令服务器只是"顺路帮忙转发"，把服务器关掉也能联机，只是要手动传两段文本。

1. **房主**：进世界 → `Esc` → **对好友开放** → **开放世界** → 点 **生成直连码**
   （等 1~3 秒，剪贴板会自动拿到 `npair1....`，也可以点 **复制直连码** 再取一次）
2. 房主把这段码通过任意渠道发给好友（游戏聊天/QQ/微信都行）
3. **好友**：主菜单 → **好友** → **添加好友** → 把码粘到第 1 行 → 点 **处理**
   → 第 3 行自动出现 `npans1....` 应答码，并已复制到剪贴板
4. 好友把应答码发回给房主
5. **房主**：同样进 **好友 → 添加好友**，把应答码粘进去 → **处理**
   → 好友那边自动进入世界

注意：

- 码里包含各自的公网候选地址，**UDP 映射有超时**，最好 1 分钟内完成一来一回，否则让对方重新生成。
- 直连码是一次性的：再点一次「生成直连码」会让旧的应答码失效（`直连码已过期`）。
- 好友白名单仍然生效：生成时只选了某个好友，别人粘贴会被拒绝（`房主没有允许你进入`）。
- 无信令时好友列表显示"离线"，点行不会发申请——这是预期，用上面的直连码流程。

## 8. Workers 信令部署检查单

1. `npx wrangler deploy` 成功，`curl https://<worker>.workers.dev/` 返回
   `{"ok":true,"service":"netplayer-signal","protocol":1,"transport":"websocket"}`。
2. **自定义域名必须同时有 DNS 记录**：Route 只匹配"已经到了 Cloudflare 的请求"，
   不会自动创建记录。做到二者之一：
   - Workers → 你的 Worker → Settings → **Domains & Routes → Custom Domain** 添加
     `netplayer.<zone>`（Cloudflare 自动建记录 + 证书），或
   - DNS → 添加 `A` 记录：Name `netplayer`，Content `192.0.2.1`（占位），**代理开启（橙色云）**，
     这样已有的 Route `netplayer.<zone>/*` 才会被触发。
3. 验证 DNS 真的存在（NXDOMAIN = 记录没加）：
   ```bash
   curl -s "https://dns.alidns.com/resolve?name=netplayer.clevebitr.dpdns.org&type=A"
   # Status:0 + Answer 里有 104.x/172.x 才对；Status:3 就是 NXDOMAIN
   ```
4. 网络：`*.workers.dev` 在部分网络不可直连，游戏 JVM 也不吃系统代理，
   所以要在 **好友 → 设置** 里填 HTTP 代理（如 `127.0.0.1:7890`），并点 **测试连接** 验证。
5. 客户端地址字段写 `wss://netplayer.<zone>/`（或 `wss://<worker>.workers.dev/`）。
