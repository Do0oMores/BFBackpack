# Battlefield 第二阶段 API 接入（API v3）

## 依赖与服务发现

构建 `mvn install` 后，桥接插件只编译依赖 API classifier，并排除实现依赖：

```xml
<dependency>
  <groupId>top.mores</groupId>
  <artifactId>BFBackpack</artifactId>
  <version>2.0.0-SNAPSHOT</version>
  <classifier>api</classifier>
  <scope>provided</scope>
  <exclusions><exclusion><groupId>*</groupId><artifactId>*</artifactId></exclusion></exclusions>
</dependency>
```

Bukkit 桥接插件 `plugin.yml` 声明 `depend: [BFBackpack]`。不要把 API 类再次 shade 到桥接插件；API/DTO 必须由同一个 Bukkit 插件 ClassLoader 提供。

```java
BFBackpackAPI api = Bukkit.getServicesManager().load(BFBackpackAPI.class);
if (api == null) {
    // 数据库异步初始化尚未完成；等待 ServiceRegisterEvent 后重新发现。
    return;
}
LoadoutResult<BFBackpackAPI> checked = new BFBackpackAPIProvider(api).requireVersion(3);
if (!checked.successful()) {
    // API_INCOMPATIBLE，不继续调用。
    return;
}
```

监听 `ServiceUnregisterEvent` 并停止使用卸载的服务。初始化中或玩家未加载、已退出时读取/修改返回 `NOT_READY`。永久解锁的 grant/revoke/list 支持离线 UUID；其他接口要求在线已加载会话。

## 查询和修改

完整、只依赖 API jar 的示例在 [BattlefieldApiExample.java](examples/BattlefieldApiExample.java)，可用 `tools/verify-artifacts.ps1` 实际编译。

| 目的 | 方法 | 返回 DTO |
| --- | --- | --- |
| 兵种武器/装备列表 | `getCatalog(uuid, type)` | `List<WeaponCatalogEntry>` |
| 按槽位、TAG 筛选 | `getCatalog(uuid, type, slot, tags)` | 同上，含解锁与访问状态 |
| 附件列表与解锁状态 | `getAttachmentCatalog(uuid, weaponId)` | `List<AttachmentCatalogEntry>` |
| 单项解锁状态 | `isUnlocked(uuid, itemId)` | `UnlockResult` |
| 永久解锁列表 | `getPlayerUnlocks(uuid)` | 不可变 `Set<String>` |
| 管理授权/撤销 | `grantUnlock` / `revokeUnlock` | `UnlockResult` |
| 四兵种当前配装 | 对四个 `BackpackType` 调用 `getLoadout` | `ClassLoadout` |
| 修改主武器/其他槽 | `selectEquipment(uuid, type, slot, id, revision)` | 提交后的 `ClassLoadout` |
| 恢复默认 | `resetSlot(uuid, type, slot, revision)` | 同上 |
| 保存记录的有效性/回退 | `validateLoadout(uuid, type)` | `LoadoutSnapshot` |
| 每把武器当前改装 | `getWeaponCustomization(uuid, weaponId)` | `WeaponCustomization` |
| 修改/移除附件 | `selectAttachment` / `removeAttachment` | 提交后的 `WeaponCustomization` |
| 整体保存改装 | `saveWeaponCustomization(plan)` | 同上，使用 plan.revision |
| 独立默认兵种 | `getPreferredClass` / `setPreferredClass` | `BackpackType` |
| 完整部署描述 | `getDeploymentLoadout(uuid, type)` | `DeploymentLoadout` |
| 注册运行时校验器 | `registerValidators(providerId, provider)` | `ValidationRegistration` |
| 旧版同步注册（已弃用） | `installProviderValidators(...)` | 无返回值 |

`WeaponCatalogEntry.unlock` 表示解锁来源及是否永久，`access` 可进一步表示必需权限缺失。列表已经按兵种规则筛选；未解锁条目仍可显示为锁定。解锁不绕过兵种限制。

修改示例：

```java
api.getLoadout(playerId, BackpackType.ASSAULT).thenCompose(read -> {
    if (!read.successful()) return CompletableFuture.completedFuture(read);
    return api.selectEquipment(playerId, BackpackType.ASSAULT, LoadoutSlot.PRIMARY,
            "battlefield:gewehr43", read.value().revision());
}).thenAccept(result -> {
    // 此处不保证 Bukkit 主线程；UI/消息需通过桥接插件调度回主线程。
    // REVISION_CONFLICT：重新读当前状态并让玩家确认，不能静默无限重试覆盖。
});
```

每名玩家每把枪的附件独立于兵种配装保存。改变配装只改变装备 ID；重新选择该枪会读取该玩家该枪最后确认的附件。相同枪在不同兵种引用同一个玩家枪械改装方案。

## 部署：两阶段、线程边界与超时

`getDeploymentLoadout` 不再由数据库线程执行外部校验，而是固定两阶段：

| 阶段 | 执行线程 | 做什么 | 不做什么 |
| --- | --- | --- | --- |
| 0 权限捕获 | Bukkit 主线程 | 读取权限节点，记录捕获时间与会话代次 | 不做数据库操作 |
| 1 目录与授权 | BFBackpack 数据库工作线程 | 校验 TAG/兵种/槽位/解锁/必需权限，解析有效回退，冻结不可变计划 | 不调用任何 Forge/TaCZ 代码 |
| 2 运行时校验 | BFBackpack 校验线程池 | 调用 `ValidationProvider`，按槽位汇总 | 不读写 SQL，不调用 Bukkit 权限 API |

由此保证：

- 数据库工作线程既不执行也不等待外部校验；适配器阻塞只占用一个有界校验槽位。
- Bukkit 主线程从不阻塞等待数据库结果，只提交一次权限快照。
- 返回 Future 在**校验线程或超时线程**完成，不保证在 Bukkit 主线程完成；禁止在游戏线程 `join/get`。
- 单次请求的所有槽位校验共享一个 `runtime-validation.timeout-ms` 预算，超时后明确返回 `VALIDATION_TIMEOUT`。
- 未验证资源永远不可能 `deployable=true`；没有注册校验器时为 `PROVIDER_UNAVAILABLE`。

`DeploymentLoadout` 结构：

```java
public record DeploymentLoadout(int protocolVersion, DeploymentBinding binding, LoadoutSnapshot loadout,
        Map<LoadoutSlot, DeploymentEquipment> equipment, List<ValidationIssue> runtimeIssues,
        ResultCode status, boolean deployable) {
    // deployable 恒等于 (status == OK)，构造时校验，避免两个字段不一致
}
```

- `protocolVersion`：当前为 **2**。
- `binding`：`playerId`、`soldierClass`、`loadoutRevision`、`catalogRevision`、`sessionGeneration`、`permissionCapturedAt`、`providerId`、`providerGeneration`、`stillCurrent`。描述只可能来自这一组版本，不会与另一版本混合。
- `loadout`：数据库确认的配装、有效回退、目录层 `issues`（`savedId`/`code`/`fallbackId`）。
- `equipment`：每槽 `WeaponDefinition`、`WeaponCustomization`（含该枪改装 revision）、按附件槽索引的 `AttachmentDefinition`、`CompatibilityInfo`。
- `runtimeIssues`：逐槽运行时问题，`code` 为 `RESOURCE_NOT_FOUND`、`ATTACHMENT_INVALID`、`VALIDATION_TIMEOUT`、`PERMISSION_STALE` 等，`reason` 不含堆栈与路径。
- `status`：部署分类，`OK` 之外全部不可部署。

校验期间发生下列任一变化，描述会以 `REVISION_CONFLICT` 返回，`binding.stillCurrent() == false`：

1. 同一兵种的配装 revision 变化；
2. 任一相关枪械的改装 revision 变化；
3. 目录热重载（`catalogRevision` 变化）。

玩家退出时以 `NOT_READY` 返回；退出后重连是新的会话代次，旧描述不会被复用。

`WeaponDefinition.gunId` 和 `AttachmentDefinition.providerId` 是资源 ID；`customization.attachments` 保存内部附件 ID，不能混用。`skinId` 当前只允许空字符串。

`CompatibilityInfo` 为 `(State state, ResultCode detail, String reason)`，`State` 为 `VERIFIED` / `UNVERIFIED` / `REJECTED`。`detail` 让部署结果能区分“资源不存在”“附件不兼容”“未安装校验器”，而不是统一返回一个笼统错误。

即使部署失败，`value` 通常仍包含只读描述（除 `NOT_READY`/`INVALID_INPUT` 等早期失败），可用于第二阶段 GUI 展示；其中不含数据库连接信息、脚本路径或异常堆栈。

## 运行时校验器：注册、替换、注销

```java
public interface ValidationProvider {
    default int apiVersion() { return BFBackpackAPI.API_VERSION; }
    CompletableFuture<CompatibilityInfo> validateWeapon(WeaponDefinition weapon);
    CompletableFuture<CompatibilityInfo> validateAttachments(WeaponDefinition weapon, WeaponCustomization customization);
}

public interface ValidationRegistration extends AutoCloseable {
    String providerId();
    long generation();
    boolean active();
    @Override void close();
}
```

约定：

- 注册是**进程级单例**：`registerValidators` 原子替换上一个注册，旧句柄立即变为非活动。
- `ValidationRegistration.close()` 只注销自己；用旧句柄注销新注册是无效操作（按实例身份比较）。
- 同一 `providerId` 重复注册等价于替换；`providerId` 必须是 `[a-z0-9_.-]{1,48}`。
- `provider.apiVersion()` 必须等于 `BFBackpackAPI.API_VERSION`，否则注册抛出 `IllegalArgumentException`（`API_INCOMPATIBLE`），且不会破坏已有注册。
- 校验器未注册时部署返回 `PROVIDER_UNAVAILABLE`；注销后新请求立即不再进入该适配器。
- 已接受的请求使用“接受时”的注册快照完成或超时；不会中途切换适配器，也不会把旧结果写进新配装。
- 插件停用时清理全部注册，并释放对适配器对象的引用，避免长期持有已卸载插件的 ClassLoader。

Bukkit 桥接插件应通过插件入口注册，以便所有者校验与自动注销：

```java
// 必须在 Bukkit 主线程调用；owner 是真实 Plugin 对象，而不是客户端可伪造的字符串。
ValidationRegistration registration = bfBackpack.registerValidators(this, "battlefield-bridge", new TaCzValidator(forgeExecutor));
```

`BFBackpack` 监听 `PluginDisableEvent`，当 `owner` 停用时自动 `close()` 该插件的全部注册。无法提供 `Plugin` 对象的场景应自行在 `onDisable()` 关闭句柄，并注意不要长期持有适配器引用。

旧接口 `installProviderValidators(weapons, attachments)` 保留兼容：内部包装为同步适配器，注册 ID 为 `legacy-sync`，无所有者、无法单独注销，会被下一次注册替换。新代码不应继续使用。若同步适配器阻塞，它只占用一个校验槽位，不会拖住数据库线程，但仍建议尽早迁移到 `ValidationProvider`。

## 权限有效性

- 权限在 Bukkit 主线程按请求重新读取，不跨请求缓存；禁止在数据库线程调用 `Player#hasPermission`。
- `PermissionSnapshot` 记录 `playerId`、`online`、`granted`、`capturedAt`、`generation`（加入代次）。
- **查询**允许使用本次请求捕获的快照。
- **配装修改**（选择装备、重置槽位、修改/移除附件、整体保存改装、设置默认兵种）在数据库线程执行前检查快照年龄；超过 `permissions.max-snapshot-age-ms` 返回 `PERMISSION_STALE`，不写入任何数据。
- **部署**在读库前重新捕获权限，并在准备阶段再次检查年龄；过期直接 `PERMISSION_STALE`，不会用旧权限授权。
- 玩家退出后旧会话的权限快照失效：会话对象被移除，请求返回 `NOT_READY`；重连生成新的 `generation`，旧快照与旧描述都不会被复用。
- 临时权限（`unlock.permission`）被撤销后，保存的配装仍保留原 ID，但有效装备回退为配置默认，`loadout.issues` 记录 `LOCKED`；永久解锁不受影响，撤销临时权限也不会删除永久解锁数据。

## 错误码与调用约束

| 错误码 | 处理 |
| --- | --- |
| `OK` | 已查询成功、事务已提交或全部资源已验证 |
| `NOT_READY` | 初始化/玩家加载中、会话已离开、后端停用或校验引擎关闭 |
| `NOT_FOUND` | 未知或禁用的武器/附件 |
| `LOCKED` | 无默认、永久或临时权限解锁 |
| `CLASS_DENIED` | 兵种/TAG/槽位/单武器限制不允许 |
| `PERMISSION_DENIED` | 缺少武器额外必需权限；命令层也使用该码 |
| `PERMISSION_STALE` | 权限快照超过 `permissions.max-snapshot-age-ms`，需重新请求 |
| `ATTACHMENT_INVALID` | 错槽位、不兼容、禁用改装或不支持的皮肤 |
| `RESOURCE_NOT_FOUND` | 提供方不存在该枪械/附件真实资源 |
| `LOADOUT_INVALID` | 配装没有完整有效装备（至少一个槽位缺有效默认） |
| `VALIDATION_TIMEOUT` | 运行时校验超过 `runtime-validation.timeout-ms` |
| `VALIDATION_BUSY` | 校验并发/队列已满，本次未执行也未授权 |
| `REVISION_CONFLICT` | CAS 冲突，或校验期间配装/改装/目录已变化 |
| `DATABASE_ERROR` | 操作未确认成功；日志含详细异常，返回值不含连接敏感信息 |
| `PROVIDER_UNAVAILABLE` | 未安装校验器、资源未验证或适配器异常 |
| `API_INCOMPATIBLE` | API ABI 或 JSON 协议版本不匹配 |
| `INVALID_INPUT` | 非法 ID、未知查询 TAG、参数错误 |
| `CONFIGURATION_ERROR` | 重载结构错误或槽位无可恢复默认 |

所有集合均不可变；调用方自行复制后修改。数据库错误不静默切换为空配置、不覆盖冲突。跨实例写入冲突后重新加载会话以便再次读取；重新加载失败则会话进入 `NOT_READY`。

API 面向可信服务器代码。桥接层必须把请求绑定到经过认证的玩家 UUID，并对管理能力另行授权；不得把任意 UUID 和 grant/revoke 直接暴露给客户端。

## 跨 ClassLoader

Forge Mod 与 Bukkit 插件可能各自加载同名 API 类。不要直接强转两个 ClassLoader 的 `BFBackpackAPI`、枚举或 record。推荐第二阶段提供一个 Bukkit 侧桥接插件，使用此 API，再通过共享 DTO 协议传递给 Forge。`ValidationProvider` 也应实现在桥接插件一侧，由它把工作转交 Forge 线程。

目前额外提供只使用 JDK 类型的反射读取边界：

```java
// plugin 对象来自 Mohist/Bukkit 桥接层。
int apiVersion = (Integer) plugin.getClass().getMethod("getApiVersion").invoke(plugin);
int protocol = (Integer) plugin.getClass().getMethod("getProtocolVersion").invoke(plugin);
if (apiVersion != 3 || protocol != 2) throw new IllegalStateException("API_INCOMPATIBLE");
@SuppressWarnings("unchecked")
CompletableFuture<String> json = (CompletableFuture<String>) plugin.getClass()
        .getMethod("getDeploymentJson", String.class, String.class, int.class)
        .invoke(plugin, playerId.toString(), "ASSAULT", 2);
json.thenAccept(envelope -> {
    // 用 Forge 自己的 JSON 库解析 code/value/message，验证协议后再使用。
});
```

此读取边界不是独立客户端网络协议。修改操作由 Bukkit 桥接插件经 typed API 执行；第二阶段再定义其 Forge 侧传输方式。

## 占位资源

`weapons.yml` 中所有 `provider: BFEXAMPLE` 与 `gun-id: example:*` 都是开发占位数据，只用于验证目录/规则/解锁/部署的数据流。真实适配器必须把它们判定为 `UNVERIFIED` 或 `RESOURCE_NOT_FOUND`，因此占位装备永远不可能通过部署校验。

第二阶段替换步骤：把 `provider` 改为真实提供方（如 `TACZ`）、把 `gun-id` 改为真实资源 ID、注册 `ValidationProvider`。三者缺一不可。
