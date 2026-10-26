# 文件变更清单

相对 master（7f3e530）的完整清单。没有删除文件。

| 状态 | 文件 | 原因 |
| --- | --- | --- |
| 修改 | `.gitignore` | 保留原忽略规则，增加本地工作目录忽略 |
| 修改 | `pom.xml` | 2.0 版本、独立 API classifier、JUnit、YAML/JSON 及打包隔离 |
| 修改 | `src/main/java/top/mores/bFBackpack/BFBackpack.java` | 异步初始化、API 服务注册、桥接版本握手和有序停用 |
| 修改 | `src/main/java/top/mores/bFBackpack/backpack/BackpackType.java` | 保留四兵种英文 ID，移除 Bukkit Material 依赖以支持独立 API |
| 修改 | `src/main/java/top/mores/bFBackpack/config/PluginConfig.java` | 适配纯 Java 兵种枚举，校验数据库类型与合法表前缀 |
| 修改 | `src/main/java/top/mores/bFBackpack/storage/Database.java` | 复用连接/执行器；分离旧表初始化并保证队列排空后关闭 |
| 修改 | `src/main/java/top/mores/bFBackpack/storage/SqlBackpackRepository.java` | 保留旧实现，移除冲突强制覆盖和擅自提升 revision |
| 修改 | `src/main/resources/config.yml` | 说明 v2 与保留旧配置的生效边界 |
| 修改 | `src/main/resources/plugin.yml` | 更新命令、权限、说明及 Maven 版本过滤 |
| 新增 | `README.md` | 构建、安装、配置、测试命令和 API 接入总览 |
| 新增 | `docs/API.md` | API 示例、数据库/线程说明及交付验收记录 |
| 新增 | `docs/CHANGES.md` | API 示例、数据库/线程说明及交付验收记录 |
| 新增 | `docs/DATABASE.md` | API 示例、数据库/线程说明及交付验收记录 |
| 新增 | `docs/DELIVERY.md` | API 示例、数据库/线程说明及交付验收记录 |
| 新增 | `docs/examples/BattlefieldApiExample.java` | API 示例、数据库/线程说明及交付验收记录 |
| 新增 | `src/main/java/top/mores/bFBackpack/api/AttachmentCatalogEntry.java` | 不依赖 Bukkit 的稳定公共 API、不可变 DTO、错误码及运行时校验扩展点 |
| 新增 | `src/main/java/top/mores/bFBackpack/api/AttachmentCompatibilityValidator.java` | 不依赖 Bukkit 的稳定公共 API、不可变 DTO、错误码及运行时校验扩展点 |
| 新增 | `src/main/java/top/mores/bFBackpack/api/AttachmentDefinition.java` | 不依赖 Bukkit 的稳定公共 API、不可变 DTO、错误码及运行时校验扩展点 |
| 新增 | `src/main/java/top/mores/bFBackpack/api/BFBackpackAPI.java` | 不依赖 Bukkit 的稳定公共 API、不可变 DTO、错误码及运行时校验扩展点 |
| 新增 | `src/main/java/top/mores/bFBackpack/api/BFBackpackAPIProvider.java` | 不依赖 Bukkit 的稳定公共 API、不可变 DTO、错误码及运行时校验扩展点 |
| 新增 | `src/main/java/top/mores/bFBackpack/api/ClassLoadout.java` | 不依赖 Bukkit 的稳定公共 API、不可变 DTO、错误码及运行时校验扩展点 |
| 新增 | `src/main/java/top/mores/bFBackpack/api/CompatibilityInfo.java` | 不依赖 Bukkit 的稳定公共 API、不可变 DTO、错误码及运行时校验扩展点 |
| 新增 | `src/main/java/top/mores/bFBackpack/api/DeploymentEquipment.java` | 不依赖 Bukkit 的稳定公共 API、不可变 DTO、错误码及运行时校验扩展点 |
| 新增 | `src/main/java/top/mores/bFBackpack/api/DeploymentLoadout.java` | 不依赖 Bukkit 的稳定公共 API、不可变 DTO、错误码及运行时校验扩展点 |
| 新增 | `src/main/java/top/mores/bFBackpack/api/LoadoutResult.java` | 不依赖 Bukkit 的稳定公共 API、不可变 DTO、错误码及运行时校验扩展点 |
| 新增 | `src/main/java/top/mores/bFBackpack/api/LoadoutSlot.java` | 不依赖 Bukkit 的稳定公共 API、不可变 DTO、错误码及运行时校验扩展点 |
| 新增 | `src/main/java/top/mores/bFBackpack/api/LoadoutSnapshot.java` | 不依赖 Bukkit 的稳定公共 API、不可变 DTO、错误码及运行时校验扩展点 |
| 新增 | `src/main/java/top/mores/bFBackpack/api/ResultCode.java` | 不依赖 Bukkit 的稳定公共 API、不可变 DTO、错误码及运行时校验扩展点 |
| 新增 | `src/main/java/top/mores/bFBackpack/api/UnlockResult.java` | 不依赖 Bukkit 的稳定公共 API、不可变 DTO、错误码及运行时校验扩展点 |
| 新增 | `src/main/java/top/mores/bFBackpack/api/ValidationIssue.java` | 不依赖 Bukkit 的稳定公共 API、不可变 DTO、错误码及运行时校验扩展点 |
| 新增 | `src/main/java/top/mores/bFBackpack/api/WeaponCatalogEntry.java` | 不依赖 Bukkit 的稳定公共 API、不可变 DTO、错误码及运行时校验扩展点 |
| 新增 | `src/main/java/top/mores/bFBackpack/api/WeaponCustomization.java` | 不依赖 Bukkit 的稳定公共 API、不可变 DTO、错误码及运行时校验扩展点 |
| 新增 | `src/main/java/top/mores/bFBackpack/api/WeaponDefinition.java` | 不依赖 Bukkit 的稳定公共 API、不可变 DTO、错误码及运行时校验扩展点 |
| 新增 | `src/main/java/top/mores/bFBackpack/api/WeaponProviderValidator.java` | 不依赖 Bukkit 的稳定公共 API、不可变 DTO、错误码及运行时校验扩展点 |
| 新增 | `src/main/java/top/mores/bFBackpack/catalog/CatalogSnapshot.java` | 规范化 ID、YAML 校验、目录快照、TAG 和兵种槽位规则 |
| 新增 | `src/main/java/top/mores/bFBackpack/catalog/ClassEquipmentRules.java` | 规范化 ID、YAML 校验、目录快照、TAG 和兵种槽位规则 |
| 新增 | `src/main/java/top/mores/bFBackpack/catalog/Ids.java` | 规范化 ID、YAML 校验、目录快照、TAG 和兵种槽位规则 |
| 新增 | `src/main/java/top/mores/bFBackpack/catalog/WeaponCatalog.java` | 规范化 ID、YAML 校验、目录快照、TAG 和兵种槽位规则 |
| 新增 | `src/main/java/top/mores/bFBackpack/command/LoadoutCommand.java` | 控制台/玩家管理命令、权限校验、异步结果及 Tab 补全 |
| 新增 | `src/main/java/top/mores/bFBackpack/listener/BukkitPermissionGateway.java` | 玩家会话生命周期和主线程权限快照边界 |
| 新增 | `src/main/java/top/mores/bFBackpack/listener/LoadoutListener.java` | 玩家会话生命周期和主线程权限快照边界 |
| 新增 | `src/main/java/top/mores/bFBackpack/service/AttachmentValidator.java` | 解锁、附件、配装校验、确认状态会话和异步 API 编排 |
| 新增 | `src/main/java/top/mores/bFBackpack/service/LoadoutService.java` | 解锁、附件、配装校验、确认状态会话和异步 API 编排 |
| 新增 | `src/main/java/top/mores/bFBackpack/service/LoadoutSession.java` | 解锁、附件、配装校验、确认状态会话和异步 API 编排 |
| 新增 | `src/main/java/top/mores/bFBackpack/service/LoadoutValidator.java` | 解锁、附件、配装校验、确认状态会话和异步 API 编排 |
| 新增 | `src/main/java/top/mores/bFBackpack/service/PermissionGateway.java` | 解锁、附件、配装校验、确认状态会话和异步 API 编排 |
| 新增 | `src/main/java/top/mores/bFBackpack/service/PermissionSnapshot.java` | 解锁、附件、配装校验、确认状态会话和异步 API 编排 |
| 新增 | `src/main/java/top/mores/bFBackpack/service/UnlockService.java` | 解锁、附件、配装校验、确认状态会话和异步 API 编排 |
| 新增 | `src/main/java/top/mores/bFBackpack/service/WeaponCustomizationService.java` | 解锁、附件、配装校验、确认状态会话和异步 API 编排 |
| 新增 | `src/main/java/top/mores/bFBackpack/storage/BackendSchema.java` | 新后端仓储接口、schema、JSON、事务和严格 SQL 乐观锁 |
| 新增 | `src/main/java/top/mores/bFBackpack/storage/LoadoutJson.java` | 新后端仓储接口、schema、JSON、事务和严格 SQL 乐观锁 |
| 新增 | `src/main/java/top/mores/bFBackpack/storage/LoadoutPlayerData.java` | 新后端仓储接口、schema、JSON、事务和严格 SQL 乐观锁 |
| 新增 | `src/main/java/top/mores/bFBackpack/storage/LoadoutRepository.java` | 新后端仓储接口、schema、JSON、事务和严格 SQL 乐观锁 |
| 新增 | `src/main/java/top/mores/bFBackpack/storage/RevisionConflictException.java` | 新后端仓储接口、schema、JSON、事务和严格 SQL 乐观锁 |
| 新增 | `src/main/java/top/mores/bFBackpack/storage/SqlLoadoutRepository.java` | 新后端仓储接口、schema、JSON、事务和严格 SQL 乐观锁 |
| 新增 | `src/main/java/top/mores/bFBackpack/storage/UnlockRepository.java` | 新后端仓储接口、schema、JSON、事务和严格 SQL 乐观锁 |
| 新增 | `src/main/java/top/mores/bFBackpack/storage/WeaponCustomizationRepository.java` | 新后端仓储接口、schema、JSON、事务和严格 SQL 乐观锁 |
| 新增 | `src/main/resources/attachments.yml` | 带中文注释的示例装备/附件目录和四兵种默认规则 |
| 新增 | `src/main/resources/class-rules.yml` | 带中文注释的示例装备/附件目录和四兵种默认规则 |
| 新增 | `src/main/resources/weapons.yml` | 带中文注释的示例装备/附件目录和四兵种默认规则 |
| 新增 | `src/test/java/top/mores/bFBackpack/BackendFixture.java` | JUnit 自动化验证（真实 SQLite、可选 MySQL） |
| 新增 | `src/test/java/top/mores/bFBackpack/CatalogTest.java` | JUnit 自动化验证（真实 SQLite、可选 MySQL） |
| 新增 | `src/test/java/top/mores/bFBackpack/LoadoutServiceTest.java` | JUnit 自动化验证（真实 SQLite、可选 MySQL） |
| 新增 | `src/test/java/top/mores/bFBackpack/MySqlIntegrationTest.java` | JUnit 自动化验证（真实 SQLite、可选 MySQL） |
| 新增 | `src/test/java/top/mores/bFBackpack/PersistenceTest.java` | JUnit 自动化验证（真实 SQLite、可选 MySQL） |
| 新增 | `src/test/java/top/mores/bFBackpack/PluginContractTest.java` | JUnit 自动化验证（真实 SQLite、可选 MySQL） |
| 新增 | `tools/ArtifactSmoke.java` | 验证真实 shaded 产物和仅 API jar 编译的可复现工具 |
| 新增 | `tools/verify-artifacts.ps1` | 验证真实 shaded 产物和仅 API jar 编译的可复现工具 |

## 加固轮次（PR #1）

在 `feature/loadout-backend-v2` 上对审查问题做局部修复，未删除任何既有实现或测试。

| 状态 | 文件 | 原因 |
| --- | --- | --- |
| 新增 | `src/main/java/top/mores/bFBackpack/api/ValidationProvider.java` | 异步运行时校验 SPI，替代在数据库线程同步回调 |
| 新增 | `src/main/java/top/mores/bFBackpack/api/ValidationRegistration.java` | 身份绑定的注册句柄，支持替换/注销 |
| 新增 | `src/main/java/top/mores/bFBackpack/api/SyncValidationProvider.java` | 旧同步校验器的安全适配器 |
| 新增 | `src/main/java/top/mores/bFBackpack/api/DeploymentBinding.java` | 部署描述绑定的玩家/版本/权限/校验器代次 |
| 新增 | `src/main/java/top/mores/bFBackpack/service/ValidationEngine.java` | 有界校验线程池、超时、并发上限、注册表与停用清理 |
| 新增 | `src/main/java/top/mores/bFBackpack/service/DeploymentPlan.java` | 两阶段部署的不可变计划与逐槽汇总 |
| 新增 | `src/main/java/top/mores/bFBackpack/listener/ValidationOwners.java` | 按 Bukkit 插件所有者跟踪并在停用时注销校验器 |
| 修改 | `api/BFBackpackAPI.java` | API_VERSION→3、PROTOCOL_VERSION→2，新增 `registerValidators`，弃用 `installProviderValidators` |
| 修改 | `api/CompatibilityInfo.java` | 增加 `detail` 错误分类与工厂方法，供精确错误码使用 |
| 修改 | `api/DeploymentLoadout.java` | 增加 `binding`、`runtimeIssues`、`status`，`deployable()` 改为派生方法 |
| 修改 | `api/ResultCode.java` | 新增 `LOADOUT_INVALID`、`RESOURCE_NOT_FOUND`、`VALIDATION_TIMEOUT`、`VALIDATION_BUSY`、`PERMISSION_STALE` |
| 修改 | `api/WeaponProviderValidator.java`、`api/AttachmentCompatibilityValidator.java` | 注明改为在校验线程执行，不再在数据库线程 |
| 修改 | `service/LoadoutService.java` | 两阶段部署、修改类操作拒绝过期权限快照、注册委托、停用时关闭引擎 |
| 修改 | `service/PermissionSnapshot.java` | 增加 `playerId`、`capturedAt`、`generation` 与过期判断 |
| 修改 | `service/LoadoutSession.java` | 增加加入代次，用于绑定权限快照与部署描述 |
| 修改 | `listener/BukkitPermissionGateway.java` | 快照填充玩家、捕获时间与代次占位 |
| 修改 | `config/PluginConfig.java` | 读取 `runtime-validation` 与 `permissions.max-snapshot-age-ms` |
| 修改 | `BFBackpack.java` | 创建校验引擎、按 Plugin 所有者注册、停用顺序清理 |
| 修改 | `resources/config.yml` | 新增 `runtime-validation` 与 `permissions` 段 |
| 修改 | `resources/weapons.yml` | 占位 provider `BFEXAMPLE`；新增医疗/烟雾/补给/修理/侦察/信标装备 |
| 修改 | `resources/class-rules.yml` | 四兵种 GADGET 槽位按职责区分默认装备 |
| 新增 | `src/test/java/top/mores/bFBackpack/RuntimeValidationTest.java` | 异步校验、超时、饱和、线程边界、异常兜底 |
| 新增 | `src/test/java/top/mores/bFBackpack/ValidationLifecycleTest.java` | 注册、重复注册、替换、注销、旧句柄、停用清理 |
| 新增 | `src/test/java/top/mores/bFBackpack/PermissionFreshnessTest.java` | 快照过期、撤销、退出重连、永久解锁独立 |
| 新增 | `src/test/java/top/mores/bFBackpack/DeploymentConsistencyTest.java` | 绑定 revision、校验中改配装/重载目录、迟到结果 |
| 修改 | `src/test/java/top/mores/bFBackpack/BackendFixture.java` | 注入校验引擎与快照延迟模拟，提供 acceptAll/drain 辅助 |
| 修改 | `src/test/java/top/mores/bFBackpack/LoadoutServiceTest.java`、`CatalogTest.java` | 同步 API v3 与新增目录条目、错误码变化 |
| 修改 | `docs/API.md`、`docs/DATABASE.md`、`docs/DELIVERY.md`、`README.md` | 线程边界、注册生命周期、超时与权限时效、错误码、占位资源 |
| 修改 | `docs/examples/BattlefieldApiExample.java` | API v3 示例：注册/注销校验器、Forge 线程调度、部署校验 |
