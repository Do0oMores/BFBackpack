# 第一阶段交付记录

## 开发总结

基于 `master` 的 `7f3e53000c98f3d886bbac89969c9615de60e5fd` 开发，已通过 GitHub 连接器核对远端 master 仍为该提交。开始时工作区干净，开发分支为 `feature/loadout-backend-v2`。

已实现服务器权威的虚拟目录、TAG 注册和筛选、四兵种六槽位独立规则、单武器额外限制、默认/永久/临时权限混合解锁、附件解锁与兼容白名单、每玩家每武器独立改装、四兵种各一套配装、独立默认兵种、SQLite/MySQL 持久化、严格 revision CAS、事务确认后更新缓存、异步会话加载、热重载、管理员命令及公共 API。

部署描述包含武器与附件实际资源 ID、保存 revision、有效回退和兼容性状态。没有真实 Adapter 时返回可检查的数据及 PROVIDER_UNAVAILABLE，不声称可生成。没有添加 Battlefield GUI、网络协议、真实物品生成/发放、复活或旧数据迁移。

原单线程数据库执行器、JDBC 连接管理和驱动选择得到复用。原 Backpack/GUI/序列化代码保留但不注册；原保存逻辑中的强制覆盖和 revision 自动提升也已移除，避免将来复用时再次掩盖冲突。

## 文件变更

完整逐文件列表及原因见 [CHANGES.md](CHANGES.md)。没有删除原项目文件，也没有删除或导入旧数据文件。

## API 与下一阶段接口

[API.md](API.md) 包含服务发现、API classifier Maven 依赖、四兵种查询、解锁、主武器变更、瞄准镜变更、revision 冲突处理、部署 DTO、错误码及 ClassLoader 边界；[BattlefieldApiExample.java](examples/BattlefieldApiExample.java) 已用独立 API jar 实际编译。

第二阶段需要实现 Bukkit 侧服务桥接、Forge 线程协调、`WeaponProviderValidator`、`AttachmentCompatibilityValidator`，并在真实生成前重新验证当前部署描述。使用 `BFBackpackAPI`、`ClassLoadout`、`WeaponCustomization`、`WeaponCatalogEntry`、`AttachmentCatalogEntry`、`LoadoutSnapshot`、`DeploymentLoadout`、`DeploymentEquipment`、`CompatibilityInfo`、`LoadoutResult` 和 `ResultCode`；不直接操作 SQL。

## 数据库

新增四张业务表 `bf_player_unlock`、`bf_weapon_customization`、`bf_player_loadout`、`bf_player_settings`，以及 `bf_backend_schema` 元数据表。主键、JSON、事务、SQL 方言、schema version、乐观锁和关闭顺序详见 [DATABASE.md](DATABASE.md)。

## 实际验证结果

验证环境：Windows、Oracle JDK 17.0.12、Maven 3.9.16。最终验证日期为 2026-10-27（Asia/Shanghai）。

| 实际命令/检查 | 结果 |
| --- | --- |
| Java 17 下 `mvn -B -ntp -DskipTests compile` | 成功；初次需补齐现有 JDBC 依赖 |
| `mvn -B -ntp test` | 成功；随后增加边界测试并以最终 verify 覆盖 |
| `mvn -o -B -ntp clean verify` | **BUILD SUCCESS**；61 个主源码文件编译；41 项测试，40 通过、0 失败、0 错误、1 跳过 |
| `tools/verify-artifacts.ps1 -JavaHome 'C:\Program Files\Java\jdk-17'` | **ARTIFACT_SMOKE_OK**；真实 shaded jar 加载 YAML/JSON、SQLite 本地驱动、跨连接恢复、MySQL 驱动可加载 |
| API classifier 隔离及示例编译 | 成功；不含 Bukkit，`jdeps` 仅返回 `java.base` |
| Bukkit `PluginDescriptionFile` 读取过滤后的 plugin.yml | 单元测试通过，版本、主类、api-version、命令和权限正确 |
| 三个目录 YAML 及 config.yml | 加载测试通过；默认目录 10 个装备和 3 个附件 |
| SQLite 建表、默认初始化、永久解锁、跨连接恢复、事务失败回滚 | 实际 JDBC 测试通过 |
| 并发修改与 revision 冲突、缓存确认状态、关闭写入排空 | 测试通过 |
| `git diff --check` | 通过 |
| 真实 MySQL 集成 | **跳过**：未提供 BF_TEST_MYSQL_* 测试数据库；仅 SQL 方言检查和打包驱动检查通过 |
| Mohist / Battlefield / TaCZ 实机联调 | **未执行**：没有服务端运行环境和真实资源适配器 |

最初普通在线 Maven 因沙箱禁止写用户缓存而失败，离线尝试也发现缺少依赖；随后通过构建权限正常下载缺失依赖。上表只将真正执行且成功的最终检查标为通过。

正常构建仍会提示依赖 JAR 的通用 MANIFEST 重叠；JDBC ServiceLoader 文件已合并，模块描述重叠已排除。SQLite 测试提示 SLF4J 未配置 binding，使用 NOP 日志后端，不影响 JDBC 功能；插件自身错误使用 java.util.logging 正式记录。

## 已知限制与验收边界

- 未运行真实 MySQL、Mohist 权限插件、控制台/玩家命令和混合 ClassLoader 联调，不能把这些项目标为实机验收通过。
- 默认 `example:*` 不保证存在。目录兼容不等于 TaCZ 运行时验证；缺少 Adapter 时部署状态必须保持不可部署。
- 皮肤只保留空 `skinId`，没有皮肤资源目录；非空值明确拒绝。
- 会话以单服为边界，未实现跨服缓存广播；SQL CAS 防止外部版本覆盖，不能代替分布式失效协议。
- 反射 JSON 边界已提供版本握手和部署读取；其他 Forge 侧调用由第二阶段 Bukkit 桥接插件经 typed API 转接。
- 原物品背包实现只作保留参考，2.0 不注册，不承诺其旧 GUI 行为继续作为产品功能。

因此代码、独立 API 与本地自动化验证已经交付；真实 MySQL 和目标混合服务端仍需环境验收。没有将未执行项目描述为测试通过。
