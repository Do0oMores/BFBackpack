# Battlefield 第二阶段 API 接入

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
LoadoutResult<BFBackpackAPI> checked = new BFBackpackAPIProvider(api).requireVersion(2);
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
| 安装运行时校验器 | `installProviderValidators(weaponValidator, attachmentValidator)` | 无返回值，可信服务器操作 |

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

## 部署、兼容性和版本

`DeploymentLoadout` 包含：

- `protocolVersion=1`。
- `loadout.saved`：数据库确认的配装及 revision，保留已失效的原 ID。
- `loadout.effectiveEquipment`：重新验证后的有效槽位，必要时使用配置默认。
- `loadout.issues`：原槽位 ID、错误码、原因和回退 ID。无有效回退的槽位阻止部署。
- `loadout.catalogRevision`：当前进程目录快照代次；重启后重新计数，不是跨服持久版本。
- `equipment`：每槽 `WeaponDefinition`、`WeaponCustomization`、按附件槽索引的 `AttachmentDefinition` 及 `CompatibilityInfo`。
- `deployable`：所有槽位有效且真实提供方和附件兼容性均确认后的结果。

`WeaponDefinition.gunId` 和 `AttachmentDefinition.providerId` 是资源 ID；`customization.attachments` 保存内部附件 ID，不能混用。`skinId` 当前只允许空字符串。

`CompatibilityInfo.State` 为 `VERIFIED` / `UNVERIFIED` / `REJECTED`。没有校验器时返回 `PROVIDER_UNAVAILABLE`，但 `value` 仍包含可检查的完整描述；不要只检查 value 非空就发放物品。

第二阶段通过 **API** 安装两个校验器，无需编译依赖内部 Service 或数据库。校验器验证真实资源、枪械支持的附件槽、附件冲突等；拿不到资源时返回 UNVERIFIED。后端在数据库工作线程调用，若需要 Forge API，应在 Adapter 中协调 Forge 线程；不得通过等待 Bukkit 主线程而与插件停用排空形成死锁。校验器应有超时、快速返回或使用已验证的资源快照。

最终发放前重新查询和验证当前描述：热重载、权限变化和后续修改都可能使旧描述失效。BFBackpack 不实现“发放事务”或物品生成。

## 跨 ClassLoader

Forge Mod 与 Bukkit 插件可能各自加载同名 API 类。不要直接强转两个 ClassLoader 的 `BFBackpackAPI`、枚举或 record。推荐第二阶段提供一个 Bukkit 侧桥接插件，使用此 API，再通过共享 DTO 协议传递给 Forge。

目前额外提供只使用 JDK 类型的反射读取边界：

```java
// plugin 对象来自 Mohist/Bukkit 桥接层。
int apiVersion = (Integer) plugin.getClass().getMethod("getApiVersion").invoke(plugin);
int protocol = (Integer) plugin.getClass().getMethod("getProtocolVersion").invoke(plugin);
if (apiVersion != 2 || protocol != 1) throw new IllegalStateException("API_INCOMPATIBLE");
@SuppressWarnings("unchecked")
CompletableFuture<String> json = (CompletableFuture<String>) plugin.getClass()
        .getMethod("getDeploymentJson", String.class, String.class, int.class)
        .invoke(plugin, playerId.toString(), "ASSAULT", 1);
json.thenAccept(envelope -> {
    // 用 Forge 自己的 JSON 库解析 code/value/message，验证协议后再使用。
});
```

此读取边界不是独立客户端网络协议。修改操作由 Bukkit 桥接插件经 typed API 执行；第二阶段再定义其 Forge 侧传输方式。

## 错误码与调用约束

| 错误码 | 处理 |
| --- | --- |
| `OK` | 已查询成功或事务已提交 |
| `NOT_READY` | 初始化/玩家加载中、会话已离开或服务停用 |
| `NOT_FOUND` | 未知或禁用的武器/附件 |
| `LOCKED` | 无默认、永久或临时权限解锁 |
| `CLASS_DENIED` | 兵种/TAG/槽位/单武器限制不允许 |
| `PERMISSION_DENIED` | 缺少武器额外必需权限；命令层也使用该码 |
| `ATTACHMENT_INVALID` | 错槽位、不兼容、禁用改装或不支持的皮肤 |
| `REVISION_CONFLICT` | 内存或 SQL CAS 版本冲突，拒绝覆盖 |
| `DATABASE_ERROR` | 操作未确认成功；日志含详细异常，返回值不含连接敏感信息 |
| `PROVIDER_UNAVAILABLE` | 未安装校验器、未验证/无效资源、不可部署或校验器异常 |
| `API_INCOMPATIBLE` | API ABI 或 JSON 协议版本不匹配 |
| `INVALID_INPUT` | 非法 ID、未知查询 TAG、参数错误 |
| `CONFIGURATION_ERROR` | 重载结构错误或槽位无可恢复默认 |

所有集合均不可变；调用方自行复制后修改。数据库错误不静默切换为空配置、不误清脏标记、不强制覆盖冲突。跨实例写入冲突后重新加载会话以便再次读取；重新加载失败则会话进入 NOT_READY。

API 面向可信服务器代码。桥接层必须把请求绑定到经过认证的玩家 UUID，并对管理能力另行授权；不得把任意 UUID 和 grant/revoke 直接暴露给客户端。
