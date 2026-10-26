# BFBackpack 2.0

Minecraft 1.20.1 / Java 17 的服务器权威配装后端。提供虚拟军械库、TAG 兵种限制、永久和权限解锁、每玩家每武器改装、四兵种各一套配装，以及异步公共 API。

本阶段仅返回数据描述，不修改 Inventory、不生成 TaCZ 枪械、不实现 Battlefield GUI、网络包或复活逻辑。`weapons.yml` 中 `provider: BFEXAMPLE` / `gun-id: example:*` 全部是开发占位资源，未经真实资源校验，永远不会被标记为可部署。

## 构建与安装

```sh
mvn clean verify
```

Windows 使用 Java 17 JDK 设置 `JAVA_HOME`。初次构建需要网络下载 Spigot API、JDBC 驱动和测试工具。

将 `target/BFBackpack-2.0.0-SNAPSHOT.jar` 放入 Bukkit/Spigot 1.20.1 或目标 Mohist 的 `plugins/`。不要把 `-api.jar` 或 `original-*.jar` 当作插件安装。JDBC 驱动随插件打包，SnakeYAML/Gson 已重定位以减少混合服务端依赖冲突。

启动后插件先异步创建数据表与目录；日志出现“BFBackpack 2.0 已就绪”后注册 API 服务。初始化期间命令返回 `NOT_READY`。首次启动会生成三个目录配置及 `config.yml`。

旧 GUI、物品序列化和背包代码保留供参考，默认不注册。没有旧数据迁移，现有数据库文件和旧表不会被删除；v2 仅创建自己的新表。

## 配置

| 文件 | 用途 | 生效方式 |
| --- | --- | --- |
| `config.yml` | SQLite/MySQL 连接及表前缀；`runtime-validation` 校验池；`permissions` 快照时效；旧 settings/backpacks 不参与 v2 | 数据库设置重启生效，其余重启生效 |
| `weapons.yml` | TAG 注册表、武器/装备定义、内部 ID、provider、实际资源 ID、解锁、额外限制 | `/bfbackpack catalog reload` |
| `attachments.yml` | 附件类型、资源 ID、解锁、武器或 TAG 兼容白名单 | 同上 |
| `class-rules.yml` | 四兵种六槽位各自的允许/禁止规则及默认装备 | 同上 |

```yaml
runtime-validation:
  timeout-ms: 3000                 # 单次部署描述的整体校验预算
  max-concurrent-validations: 8    # 校验线程池大小
  queue-capacity: 32               # 等待队列上限，也是未完成请求上限的一部分

permissions:
  max-snapshot-age-ms: 5000        # 超过该年龄的权限快照不能授权修改/部署，0 关闭检查
```

兵种固定为 `ASSAULT`、`MEDIC`、`SUPPORT`、`RECON`，中文只用于显示。槽位固定为 `PRIMARY`、`SECONDARY`、`GADGET_1`、`GADGET_2`、`GRENADE`、`MELEE`，不使用背包编号。

资源 ID 格式为 `namespace:path`，去除首尾空白并转小写，最长 128 字符。武器 ID 和 `gun-id` 分别保存；装备也使用目录 ID，不从 Material 或伪造 NBT 推断资源。

`weapons.yml` 的 `tags` 是显式 TAG 注册表。未知 TAG、槽位、兵种、非法布尔值及缺失字段都会报告具体位置。YAML 重复 key 或规则结构错误拒绝整次重载并保留旧快照；单条武器/附件错误会隔离该条，不影响其他条目。大小写规范化后重复的武器 ID 全部隔离。

规则的 `deny-items` / `deny-tags` 优先于 `allow-items` / `allow-tags`。未配置槽位拒绝装备。武器可额外配置：

```yaml
restrictions:
  allow-classes: [ASSAULT, RECON] # 空列表表示不增加兵种限制
  deny-classes: []
  permission: bfbackpack.special # 必需权限，与临时解锁权限分开
```

`unlock.permission` 是可选的临时解锁来源，不是额外使用门槛；`restrictions.permission` 是必需权限，永久解锁也不能绕过。权限每次 API 调用在 Bukkit 主线程重新读取，不跨请求缓存。修改配装与部署还会检查快照年龄（`permissions.max-snapshot-age-ms`），过期返回 `PERMISSION_STALE`，不会用旧权限授权。默认解锁或数据库永久解锁不受临时权限撤销影响。

每槽 `default` 必须引用启用、默认解锁、无额外权限且兵种/槽位合法的装备。失效默认会被隔离并输出日志。验证保存记录时不删除或覆盖旧记录，返回 `issues` 和有效回退装备；缺失合法装备/默认值的槽位会阻止部署。

附件必须指定 `allow-weapons` 或 `allow-tags`，两者为任一匹配；默认不假定任何枪支持所有附件。这仅证明目录兼容，真实 TaCZ 兼容性由第二阶段校验器决定。非空 `skinId` 在皮肤目录尚未实现时明确拒绝。

SQLite 默认使用 `plugins/BFBackpack/backpacks.db`。MySQL 修改 `database.type: mysql` 并填写连接配置；建议为专用数据库账户授予必要的建表/读写权限，勿提交真实密码。表前缀允许空值或最多 16 个字母、数字或下划线，非空时必须以字母/下划线开头。

## 管理员测试命令

控制台和玩家均可执行；玩家名称仅解析在线玩家，离线授权请使用 UUID。所有命令校验权限并支持 Tab 补全。

```text
/bfbackpack catalog list
/bfbackpack catalog reload
/bfbackpack unlock grant <玩家或UUID> <itemId>
/bfbackpack unlock revoke <玩家或UUID> <itemId>
/bfbackpack unlock check <在线玩家> <itemId>
/bfbackpack unlock list <玩家或UUID>
/bfbackpack loadout show <在线玩家> <class>
/bfbackpack loadout set <在线玩家> <class> <slot> <itemId>
/bfbackpack loadout reset <在线玩家> <class> <slot>
/bfbackpack loadout validate <在线玩家> <class>
/bfbackpack loadout deployment <在线玩家> <class>
/bfbackpack weapon show <在线玩家> <weaponId>
/bfbackpack weapon attachment <在线玩家> <weaponId> <附件槽位> <attachmentId|remove>
/bfbackpack settings show <在线玩家>
/bfbackpack settings class <在线玩家> <class>
```

示例：

```text
/bfbackpack unlock grant Steve battlefield:gewehr43
/bfbackpack loadout set Steve ASSAULT PRIMARY battlefield:gewehr43
/bfbackpack weapon attachment Steve battlefield:gewehr43 SCOPE battlefield:scope_basic
/bfbackpack loadout deployment Steve ASSAULT
```

命令返回 `OK` 或明确错误码。没有 Adapter 时，最后一条返回带完整数据的 `PROVIDER_UNAVAILABLE`，这是预期行为。

`bfbackpack.admin` 及其子权限 `bfbackpack.admin.catalog/unlock/loadout/weapon/settings` 默认仅 OP。示例临时授权节点 `bfbackpack.weapon.gewehr43` 和 `bfbackpack.attachment.scope_advanced` 默认 `false`，管理员测试权限不会自动解锁武器。

## 公共 API 与第二阶段

详见 [API 文档](docs/API.md) 和 [可编译示例](docs/examples/BattlefieldApiExample.java)。API ABI 为 **3**，跨 ClassLoader 的 JSON 协议版本为 **2**，数据库/JSON schema 为 **1**。

`target/BFBackpack-2.0.0-SNAPSHOT-api.jar` 只包含 API、DTO 和不依赖 Bukkit 的兵种枚举。Battlefield 的 Bukkit 桥接插件通过 ServicesManager 获取 `BFBackpackAPI`；Forge 侧不直接访问 SQL，不跨 ClassLoader 强转各自加载的 DTO。

返回值为 `CompletableFuture<LoadoutResult<T>>`。保存成功只在事务提交后返回；`revision` 冲突要求调用方重新读取和确认选择。Future 回调没有 Bukkit 主线程保证，不得在游戏线程调用阻塞的 `join/get`。

`getDeploymentLoadout` 分两阶段执行：数据库线程只做目录/授权校验并冻结不可变计划，真实资源校验在独立的、有界的校验线程池中进行（`runtime-validation`）。返回 Future 在校验线程或超时线程完成；单请求超时返回 `VALIDATION_TIMEOUT`，并发或排队超限返回 `VALIDATION_BUSY`，适配器异常或未注册返回 `PROVIDER_UNAVAILABLE`。描述通过 `binding` 绑定玩家、兵种、配装/改装/目录 revision、会话代次与校验器代次；校验期间任何 revision 变化都会以 `REVISION_CONFLICT` 作废该描述。

运行时校验器通过 `registerValidators(providerId, provider)` 注册，返回可 `close()` 的句柄；重复注册即替换，旧句柄无法注销新注册。Bukkit 桥接插件应使用 `BFBackpack#registerValidators(Plugin, ...)`，在 `PluginDisableEvent` 时自动注销并释放其 ClassLoader。旧的 `installProviderValidators` 仍保留但已弃用（无所有者、不可单独注销）。

只有安装校验器并逐槽验证成功才可能 `deployable=true`。最终物品创建仍由 Battlefield/TaCZ Adapter 负责，并应在创建时重新验证目录、权限、版本和资源。

## 数据库与线程

详见 [数据库与线程说明](docs/DATABASE.md)。所有 SQL（包括连接和建表）都在复用的单线程数据库执行器上运行。新系统使用提交后更新的不可变缓存，不使用脏标记或未经数据库确认的“成功”状态。

| 线程 | 职责 |
| --- | --- |
| Bukkit 主线程 | 只读取权限快照并提交异步请求；不等待数据库，不执行校验 |
| `BFBackpack-DB` | SQL、目录/授权校验、冻结部署计划；不调用 Forge/TaCZ，不等待主线程 |
| `BFBackpack-Validation` | 调用 `ValidationProvider`，按槽位汇总；不访问 SQL 与 Bukkit 权限 |
| `BFBackpack-Validation-Timeout` | 到期完成 `VALIDATION_TIMEOUT`，丢弃迟到结果 |

玩家加入异步加载，离开删除会话；每次加入有独立代次，旧加载无法覆盖重连会话。关闭时停止接收新操作、注销并释放校验器、失败未完成校验、取消未完成权限调度、排空已进入数据库队列的操作，最后关闭连接。

## 测试

```sh
mvn test
mvn clean verify
```

Windows 还可验证真实打包产物和仅 API jar 的示例编译：

```powershell
./tools/verify-artifacts.ps1 -JavaHome 'C:\Program Files\Java\jdk-17'
```

测试覆盖目录、重复 ID、无效定义、TAG、规则、权限撤销、永久解锁、玩家/枪械/兵种隔离、附件类型与兼容白名单、revision 冲突、并发保存、SQLite 重启恢复、失败回滚、关闭排空、DTO 不可变及 API 无 Bukkit 依赖。

加固轮次新增覆盖：异步校验成功/失败/异常/永不完成/超时、慢校验不阻塞数据库读写、校验池饱和、校验进行中改配装或热重载目录、迟到结果不生效；校验器注册/重复注册/替换/注销/旧句柄失效/API 版本不符/所有者释放/停用清理；权限快照过期、撤销后回退、退出重连、永久解锁与临时权限独立；部署绑定 revision 一致性。

真实 MySQL 测试默认跳过。使用专用测试库，设置 `BF_TEST_MYSQL_HOST`、`BF_TEST_MYSQL_DATABASE`、`BF_TEST_MYSQL_USER`、`BF_TEST_MYSQL_PASSWORD`（可选 `BF_TEST_MYSQL_PORT`），然后运行 `mvn test`。测试只创建并清理带随机 `bft_*` 前缀的表。未配置数据库时仅做 MySQL 方言静态检查，不声称完成真实 MySQL 验证。

本次实际验证结果、文件变更及未验证项目见 [交付记录](docs/DELIVERY.md)。

## 当前边界

尚未在真实 Mohist/Battlefield/TaCZ 环境执行玩家上线、权限插件事件、控制台命令及物品生成联调。单元测试通过不等于混合服务端已经验证。示例资源不保证存在；Adapter 缺失时任何组合均不能标记为已验证可部署。

会话缓存以单服在线玩家为边界；跨服务器修改同一记录由 SQL 乐观锁防止覆盖，冲突时重新加载。未实现分布式推送失效，第二阶段若支持玩家同时跨服在线，需要增加一致性协议。
