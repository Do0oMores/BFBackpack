# 数据库、事务和线程模型

默认表前缀 `bf_`，可通过 `database.table-prefix` 修改。所有表由 `BackendSchema` 生成，数据库 schema 版本 **1**，JSON payload 版本 **1**。不读取、迁移或删除旧 `bf_player` / `bf_backpack` 数据。

## 表和索引

| 表 | 字段 | 主键/索引 |
| --- | --- | --- |
| `bf_player_unlock` | `player_uuid CHAR(36)`, `item_id VARCHAR(128)`, `source VARCHAR(64)`, `unlocked_at BIGINT` | `(player_uuid,item_id)` |
| `bf_weapon_customization` | `player_uuid`, `weapon_id VARCHAR(128)`, `customization_json`, `revision BIGINT`, `updated_at BIGINT` | `(player_uuid,weapon_id)` |
| `bf_player_loadout` | `player_uuid`, `soldier_class VARCHAR(16)`, `schema_version INT`, `loadout_json`, `revision BIGINT`, `updated_at BIGINT` | `(player_uuid,soldier_class)` |
| `bf_player_settings` | `player_uuid`, `preferred_class VARCHAR(16)`, `updated_at BIGINT` | `player_uuid` |
| `bf_backend_schema` | `id INT`, `schema_version INT`；固定 `id=1` | `id` |

上述主键索引同时支持按玩家 UUID 的前缀查询，无额外二级索引或外键。解锁支持离线 UUID，因此不要求先有 settings 行。所有字段 NOT NULL；时间为 epoch 毫秒。

SQLite 使用 TEXT，开启 WAL、`synchronous=NORMAL`、`busy_timeout=5000`。MySQL JSON 文本用 LONGTEXT，表使用 InnoDB、utf8mb4 和二进制排序规则，保证 ID 的大小写比较语义明确。ID 在应用层统一小写。表名前缀严格验证后才能拼接；所有玩家 UUID、ID、内容和版本使用参数化 SQL。

JSON 示例：

```json
{"schemaVersion":1,"equipment":{"PRIMARY":"battlefield:stg44","SECONDARY":"battlefield:p38"}}
```

```json
{"schemaVersion":1,"attachments":{"SCOPE":"battlefield:scope_basic"},"skinId":""}
```

JSON/行 schema 不支持或损坏时明确报错，保留原记录，玩家不进入 ready。没有隐式重置或旧数据迁移。

## 事务和并发控制

`loadPlayer` 在同一事务中插入缺失的 settings 和四兵种默认行，再读取解锁、配装、改装与默认兵种。默认行 revision 为 0，只在不存在时创建，重启/目录重载不会覆盖已有数据。

配装保存采用：

```sql
UPDATE bf_player_loadout
SET loadout_json=?, schema_version=?, revision=revision+1, updated_at=?
WHERE player_uuid=? AND soldier_class=? AND revision=?
```

更新行数必须为 1，否则回滚并返回 `REVISION_CONFLICT`。改装同样使用 `(player_uuid,weapon_id,revision)` 比较；首次 revision=0 保存先在事务内插入空的 revision=0 行，再执行 CAS。失败会连同初始插入一起回滚。绝不将调用者 revision 擅自提高，也不执行无版本条件的覆盖。

grant/revoke 和默认兵种更新也使用事务；重复 grant 幂等，不重复创建解锁。SQLite 使用 `INSERT OR IGNORE`，MySQL 使用 `INSERT IGNORE`，均仅对受限且已验证字段执行初始化/幂等插入。DDL 在启动阶段执行，MySQL DDL 的隐式提交不被当作业务事务。

只有 commit 成功后才发布新的不可变 `LoadoutPlayerData` 并完成成功 Future。SQL 失败不会修改缓存。数据库发现外部版本冲突后重新读取该玩家确认状态；读取失败则清除其 ready 状态，避免继续使用未知状态。

## 线程与会话

1. Bukkit 线程读取在线玩家和权限，生成本次操作的 `PermissionSnapshot`。不保留跨请求权限缓存。
2. 操作进入复用的 `Database.executor()` 单线程队列，校验当前目录、会话代次、确认 revision 后执行事务。
3. 工作线程发布不可变确认快照并完成 Future。调用者如需 Bukkit UI/消息，必须自行调度回主线程。

连接建立、建表、查询、写入和目录热重载都在数据库队列执行。各玩家会话以 UUID 隔离，每次加入创建独立会话对象；离开立即移除，晚到的加载只写自己的旧对象，不会复活退出/重连前的缓存。

目录和规则以一个 `CatalogSnapshot` 整体替换，后台操作捕获同一代目录。不会保留旧目录推算的“已解锁”“可部署”结果；每次检查使用当前目录和本次权限。保存记录不因目录变化而被删除。

停用顺序：停止 API 接收新请求并排队清理缓存 → 完成/取消未完成的权限 Future → `executor.shutdown()` → 等待已进入队列的写入 → 关闭连接。正常等待最多 20 秒；超过时输出警告，由非 daemon 收尾线程继续等待排空并关闭连接，不在进行中的事务下面直接关闭连接。操作系统强制结束进程不保证尚未提交的操作完成。

尚未进入数据库队列、仍在等待权限调度的请求在停用时得到 NOT_READY。已经开始处理的写入可以在玩家离开后提交，但不重建该玩家缓存。

## 验证范围

JUnit 实际使用 SQLite JDBC，覆盖跨连接重启恢复、首次初始化、并发 CAS、失败回滚、重复保存和关闭排空。MySQL 方言有静态检查；`MySqlIntegrationTest` 仅在设置专用测试库环境变量时运行，没有数据库时明确跳过。未实现跨服实时缓存广播；多实例 CAS 只防止版本覆盖，不承诺分布式强一致快照。
