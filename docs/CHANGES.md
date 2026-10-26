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
