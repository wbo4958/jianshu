> 写这篇blog主要是源于一个GTS case: testDefaultGrants, 这个case的意图是测试系统里默认granted的runtime permissions. 

转载请标明来处：http://www.jianshu.com/p/ffd583f720f4

在 Android M以后， Android加入了runtime permissions, 也就是dangerous permissons, 这些权限有可能会刺探用户隐私等等危害。 这样系统在安装APP的时候就不会默认grant runtime permissions.
  
在Android M之前, Runtime permissions是直接被当作是install permissons，即在安装的时候就直接grant了。

而针对一些系统内置的app，OEM vendor可以修改代码来默认grant runtime permissions. 

所以Google的这个GTS case 就是检查是否OEM产商随意的grant runtime permission.

# 一、 系统配置权限的初始化

```
SystemConfig systemConfig = SystemConfig.getInstance();
```
该配置的初始化主要是从系统配置文件中读取自定义的权限， 包括 /etc/sysconfig /etc/permissions /oem/etc/sysconfig /oem/etc/permissions里所有的xml文件。

下图是读出来的permission相关的UML图为

![图1. System config permission UML图](http://upload-images.jianshu.io/upload_images/5688445-3019e4dacead6a91.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

```java
mGlobalGids = systemConfig.getGlobalGids();
mSystemPermissions = systemConfig.getSystemPermissions();
mAvailableFeatures = systemConfig.getAvailableFeatures();

// Propagate permission configuration in to package manager.
ArrayMap<String, SystemConfig.PermissionEntry> permConfig
        = systemConfig.getPermissions();
for (int i=0; i<permConfig.size(); i++) {
    SystemConfig.PermissionEntry perm = permConfig.valueAt(i);
    BasePermission bp = mSettings.mPermissions.get(perm.name);
    if (bp == null) {
        bp = new BasePermission(perm.name, "android", BasePermission.TYPE_BUILTIN);
        mSettings.mPermissions.put(perm.name, bp);
    }
    if (perm.gids != null) {
        bp.setGids(perm.gids, perm.perUser);
    }
}
```
以上这些代码片段主要目的是将SystemConfig里解析出来的值取出来赋值给PackageManagerService里的变量mSettings，在这个阶段解析出来的permission的sourcePackage全部命名为 **android** 因为这是系统里定义的permission.

# 二、 读取系统里的package.xml

## 2.1 mSettings.readLPw(this, …)

解析/data/system/package.xml

### 2.1.1 readPermissionsLPw

```java
if (tagName.equals("permissions")) {  
    readPermissionsLPw(mPermissions, parser);
```
在package.xml里permissions放在前面，所以这里先读的是permissions section, permissions字段,保存到mSettings.mPermissions，permissions是所有apk自定义的permissions

### 2.1.2 readPackageLPw

```java
if (tagName.equals("package")) {
    readPackageLPw(parser);   //解析 package section

void readPackageLPw(XmlPullParser parser)
   packageSetting = addPackageLPw(name.intern(), realName, …)
  //生成该package对应的 PackageSetting, 然后根据xml里值进行填充
   readInstallPermissionsLPr(parser, packageSetting.getPermissionsState());
    //读取安装权限

void readInstallPermissionsLPr(XmlPullParser parser,
        PermissionsState permissionsState)
//解析package section里的 <perms>中当前package里所有的权限

    String grantedStr = parser.getAttributeValue(null, ATTR_GRANTED);
    final boolean granted = grantedStr == null
            || Boolean.parseBoolean(grantedStr);
    if (granted) {
        permissionsState.grantInstallPermission(bp) //grant安装权限
```

### 2.1.3 grantInstallPermission

这些安装权限是apk在安装时自动grant的，都是normal的等级，不是dangeous权限。
该函数的主要作用是
- 生成permission对应的PermissionData，并用加入到PermissionsState mPermissions里
- 对用户id,grant权限，即生成PermissionState对象，并用mUserStates来track.

## 2.2 从/data/system/users/x/runtime-permissions.xml 读取runtime权限, 并grant

```java
    for (UserInfo user : users) {
        mRuntimePermissionsPersistence.readStateForUserSyncLPr(user.id);
    }
     readStateForUserSyncLPr->parseRuntimePermissionsLPr

   parseRuntimePermissionsLPr()->parsePermissionsLPr
       //解析 <pkg> 下的每一个item得到每一个权限，然后进行grant
      if (granted) {
          permissionsState.grantRuntimePermission(bp, userId);  //grant runtime权限
          permissionsState.updatePermissionFlags(bp, userId,
                PackageManager.MASK_PERMISSION_FLAGS, flags);
      }
```

如下面这个package.xml版本，摘自/data/system/package.xml

```xml
<package name="com.android.tv.settings" codePath="/system/priv-app/TvSettings" nativeLibraryPath="/system/priv-app/TvSettings/lib" primaryCpuAbi="arm64-v8a" publicFlags="944291397" privateFlags="8" ft="1539c6151e8" it="1539c6151e8" ut="1539c6151e8" version="1" sharedUserId="1000">
        <sigs count="1">
            <cert index="0" />
        </sigs>
<perms>
<item name="android.permission.WRITE_SETTINGS" granted="true" flags="0" />
<item name="android.permission.MODIFY_AUDIO_SETTINGS" granted="true" flags="0" />
<item name="android.permission.INSTALL_LOCATION_PROVIDER" granted="true" flags="0" />
```
![图2. PackageManagerService与Permissions的UML图](http://upload-images.jianshu.io/upload_images/5688445-0d5eab02622e98fe.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

注意：如果是系统第一次开机的时候，系统里是没有package.xml的，那么将不会生成package对应的PackageSetting, 在这种情况下，PackageSetting会在扫描apk文件时进行生成.

# 三、 扫描系统apk文件，解析apk文件

```java
scanDirLI -> scanPackageLI(file, …) ->
   pkg = pp.parsePackage(scanFile, parseFlags); //解析apk文件
      parseClusterPackage
      pkg = parseBaseApk(baseApk, assets, flags);
        PackageParser.Package pkg = parseBaseApk(res, …) //解析apk中的AndroidManifest.xml
```

![图3 PackageParser与Permissions的UML图](http://upload-images.jianshu.io/upload_images/5688445-3c068b8947c0ca57.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

其中PackageParser.Package代表一个解析出来的apk, 而PackageParser指的是一个解析类，

```java
scanPackageLI(pkg, xxx) -> scanPackageDirtyLI(pkg, …)
```

该步骤
- 与Permission相关的的操作主要是解析pkg即PackageParser.Package里的字段，将apk相关的信息保存到PackageManagerService里或mSettings里.
- 根据pkg里解析出来的信息生成PackageSetting

```java
pkgSetting = mSettings.getPackageLPw(pkg, …)
        PackageSetting p = getPackageLPw(name, …)
     //如果系统第一次开机启动，那么从Setting里是拿不到PackageSetting的，这时只能新生成一个，
     //并将它通过addPackageSettingLPw加入到mSettings.mPackages里;
     //如果不是第一次开机，那么将会直接从Settings.mPackages里取
```

![图4 PackageManagerService 扫描apk后的Permissions的UML图](http://upload-images.jianshu.io/upload_images/5688445-009367300659c73f.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

# 四、更新permissions.
分为两种情况
- 在读取package.xml的时候已经grant了
- 第一次开机，updatePermissionsLPw 会 grant相应的权限，

```java
updatePermissionsLPw(null, null, updateFlags)->grantPermissionsLPw(pkg, …)
//为每个package grant所请求的permissions

void grantPermissionsLPw(PackageParser.Package pkg, boolean replace, String packageOfInterest) {

final PackageSetting ps = (PackageSetting) pkg.mExtras;
if (ps == null) { return; }
//获得package的PackageSettings

PermissionsState permissionsState = ps.getPermissionsState();
//获得package的PermissionState类
PermissionsState origPermissions = permissionsState;

final int N = pkg.requestedPermissions.size();
for (int i=0; i<N; i++) { //遍历当前所有请求的permissions，即<uses-permission>
    final String name = pkg.requestedPermissions.get(i);
                   //获得permisison name
    final BasePermission bp = mSettings.mPermissions.get(name);
                  //根据permission name获得permission所表示的结构
final int level = bp.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE;
                  //获得permission的等级
switch (level) {
    case PermissionInfo.PROTECTION_NORMAL: {grant = GRANT_INSTALL}
    case PermissionInfo.PROTECTION_DANGEROUS: {grant = GRANT_RUNTIME;}
    case PermissionInfo.PROTECTION_SIGNATURE: {}
}

switch (grant) {
    case GRANT_INSTALL: {
	permissionsState.grantInstallPermission(bp)
         //为所有用户颁布安装permission，即生成permission对应的PermissionState
         //并用PermissionData的mUserStates来track
         //其实这里grant install的操作在之前 readLPw就已经执行过了，这里没什么实际用处，主要是对sharedUser 进行一些update ?????

    case GRANT_RUNTIME: {
 }
}
```

# 五、grant默认的runtime 权限

```java
mPackageManagerService.systemReady();  
int[] grantPermissionsUserIds = EMPTY_INT_ARRAY;
for (int userId : UserManagerService.getInstance().getUserIds()) {
    if (!mSettings.areDefaultRuntimePermissionsGrantedLPr(userId)) {
        grantPermissionsUserIds = ArrayUtils.appendInt(
                grantPermissionsUserIds, userId);
    }
}

// 如果是系统第一次启动，即没有runtime-permissions.xml, 那么系统会进入grant default permission的阶段.

for (int userId : grantPermissionsUserIds) {
    mDefaultPermissionPolicy.grantDefaultPermissions(userId);
}
```
为系统级的app grant默认runtime权限

```java
public void grantDefaultPermissions(int userId) {
    grantPermissionsToSysComponentsAndPrivApps(userId);
    grantDefaultSystemHandlerPermissions(userId);
}

void grantPermissionsToSysComponentsAndPrivApps(int userId) {
  for (PackageParser.Package pkg : mService.mPackages.values()) {
  if (!isSysComponentOrPersistentPlatformSignedPrivAppLPr(pkg)
        || !doesPackageSupportRuntimePermissions(pkg)
        || pkg.requestedPermissions.isEmpty()) {
   /*
   过滤出特定的apk, 
   1, sdk version >= 23的 
   2, platform签名 
   3, persistent privilege App进行 grant runtime权限
   */
    continue;
  }

  Set<String> permissions = new ArraySet<>();
  final int permissionCount = pkg.requestedPermissions.size();
  for (int i = 0; i < permissionCount; i++) {
    String permission = pkg.requestedPermissions.get(i);
    BasePermission bp = mService.mSettings.mPermissions.get(permission);
    if (bp != null && bp.isRuntime()) {
        permissions.add(permission);
    }
  }
  if (!permissions.isEmpty()) {
    grantRuntimePermissionsLPw(pkg, permissions, true, userId);
 }

void grantRuntimePermissionsLPw(PackageParser.Package pkg, Set<String> permissions, boolean systemFixed, boolean overrideUserChoice,  int userId) {
 mService.grantRuntimePermission(pkg.packageName, permission, userId);
 mService.updatePermissionFlags (permission, pkg.packageName,
        newFlags, newFlags, userId);
}

void grantRuntimePermission(String packageName, String name, final int userId) {
	int result = permissionsState.grantRuntimePermission(bp, userId);
	mOnPermissionChangeListeners.onPermissionsChanged(uid);
	mSettings.writeRuntimePermissionsForUserLPr(userId, false);
}
```

为系统默认的组件grant 默认权限

```java
void grantDefaultSystemHandlerPermissions(int userId) {

// Camera
Intent cameraIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
PackageParser.Package cameraPackage = getDefaultSystemHandlerActivityPackageLPr(
        cameraIntent, userId);
if (cameraPackage != null
        && doesPackageSupportRuntimePermissions(cameraPackage)) {
    grantRuntimePermissionsLPw(cameraPackage, CAMERA_PERMISSIONS, userId);
    grantRuntimePermissionsLPw(cameraPackage, MICROPHONE_PERMISSIONS, userId);
    grantRuntimePermissionsLPw(cameraPackage, STORAGE_PERMISSIONS, userId);
}
//获得默认的camera package,然后grant给该package  camera/microhphone/storage权限

 //将/vendor/etc/permissions中的package.xml里定义的permissions grant给这个package, 
 //注意：这个feature是OEM自己加的，不在AOSP里.
grantDefaultPermissionsForProduct(Environment.buildPath(
        Environment.getVendorDirectory(), "etc", "permissions"), userId);
}
private void grantDefaultPermissionsForProduct(File path, int userId) {
    …
    for (File f: path.listFiles()) {
        grantDefaultPermissionsFromXml(f, userId);
    }
}
```

![图5 PackageManagerService grant Permissions后的UML图](http://upload-images.jianshu.io/upload_images/5688445-2160a367a381346d.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

# 六、 安装时grant install permissions

```java
installPackageAsUser(pm install) -> INIT_COPY -> MCS_BOUND -> startCopy
 (InstallParams) -> handleReturnCode -> processPendingInstall -> installPackageLI

void installPackageLI(InstallArgs args, PackageInstalledInfo res) {
      PackageParser.Package pkg = pp.parsePackage(tmpPackageFile, parseFlags);

if (replace) {
    replacePackageLI(pkg, parseFlags, scanFlags | SCAN_REPLACING, args.user,
            installerPackageName, volumeUuid, res);
} else {
    installNewPackageLI(pkg, parseFlags, scanFlags | SCAN_DELETE_DATA_ON_FAILURES, args.user, installerPackageName, volumeUuid, res);
}
}

void installNewPackageLI(PackageParser.Package pkg, … ) {
PackageParser.Package newPackage = scanPackageLI(pkg, parseFlags, scanFlags,
        System.currentTimeMillis(), user);
 updateSettingsLI(newPackage, installerPackageName, volumeUuid, null, null, res, user);
}

//更新Settings
void updateSettingsLI(PackageParser.Package newPackage, …) {

//在这里更新permissions, grant install的权限(包括normal, signature的permission)
updatePermissionsLPw(newPackage.packageName, newPackage,
        UPDATE_PERMISSIONS_REPLACE_PKG | (newPackage.permissions.size() > 0
                ? UPDATE_PERMISSIONS_ALL : 0));

//并写入到packages.xml里
mSettings.writeLPr();
}
```

# 七、Shared User ID 的权限

在packages.xml 和 runtime-permissions.xml里有一节是 <shared-user>, 表示的是这个UID所获得的权限，

Eg, android.uid.system的install权限. (来自packages.xml)

```xml
  <shared-user name="android.uid.system" userId="1000">
        <sigs count="1">
            <cert index="0" />
        </sigs>
        <perms>
            <item name="android.permission.WRITE_SETTINGS" granted="true" flags="0" />
            <item name="android.permission.MODIFY_AUDIO_SETTINGS" granted="true" flags="0" />
            <item name="android.permission.INSTALL_LOCATION_PROVIDER" granted="true" flags="0" />
            <item name="android.permission.MANAGE_ACCOUNTS" granted="true" flags="0" />
            <item name="android.permission.SYSTEM_ALERT_WINDOW" granted="true" flags="0" />
            <item name="android.permission.GET_TOP_ACTIVITY_INFO" granted="true" flags="0" />
   </shared-user>
```

android.uid.system的runtime权限. (来自runtime-permissions.xml)

```java
  <shared-user name="android.uid.system">                                                                                        
    <item name="android.permission.ACCESS_FINE_LOCATION" granted="true" flags="30" />
    <item name="android.permission.READ_EXTERNAL_STORAGE" granted="true" flags="30" />
    <item name="android.permission.ACCESS_COARSE_LOCATION" granted="true" flags="30" />
    <item name="android.permission.CAMERA" granted="true" flags="30" />
    <item name="android.permission.GET_ACCOUNTS" granted="true" flags="30" />
    <item name="android.permission.WRITE_EXTERNAL_STORAGE" granted="true" flags="30" />
    <item name="android.permission.RECORD_AUDIO" granted="true" flags="30" />
    <item name="android.permission.READ_CONTACTS" granted="true" flags="30" />
  </shared-user>
```

在packages.xml或runtime-permissions.xml里会发现如果拥有相同uid的package，它们的grant 权限和UID在shared-user里获得的权限是一样的。（因为拥有相同UID, 且签名一样的话，他们可以彼此访问数据，理所当然应该获得所有的相同的权限）。

Shared userId的类图，如下

![图6 sharedUserId与Permissions的UML图](http://upload-images.jianshu.io/upload_images/5688445-405d068bce19be10.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

如图6可知， SharedUserSettings继承于SettingBase, 因此也就有PermissionsState 权限状态类，接下来看看android是怎样grant权限给SharedUserSettings的。

在解析每个apk文件后，通过以下代码，将sharedUser与package建立连接了。

```java
mSettings.insertPackageSettingLPw(pkgSetting, pkg); -> addPackageSettingLPw(p, pkg.packageName, p.sharedUser);
p.pkg = pkg;
sharedUser.addPackage(p); 
//加入到SharedUserSetting里的packages里，这样就把SharedUserSetting就track了package.
p.sharedUser = sharedUser;
p.appId = sharedUser.userId;
```

那么代码是如何是保证拥有一样的UID具有相同的权限呢？所有的install, signature, dangeous权限都是由该函数grant的，

```java
void grantPermissionsLPw(PackageParser.Package pkg, boolean replace,
        String packageOfInterest) {
  PermissionsState permissionsState = ps.getPermissionsState();
}

public PermissionsState getPermissionsState() {
    return (sharedUser != null)  
            ? sharedUser.getPermissionsState()
            : super.getPermissionsState();
}
```

由 getPermissionsState可以看出，在获得 PermissionsState的时候先找sharedUser，即Shared User Id的PermissionsState, 如果没有设置shared uid才用package自己的，所以可以看出来， UID的PermissionsState是所有在AndroidManifest.xml设置了sharedUserId里的permission的一个合集，且他们permission也是一样的。

# 八、 结论
1. 系统自定义的权限以及一直feature, 可以直接定义在xml(/etc/permissions下面)配置文件里.
2. 一个apk在安装的时候已经将非运行时权限直接grant给设备上所有的用户，而运行时权限是跟权限直接相关的.
3. 一个apk可以申请permission, 即用<uses-permission />表示，也可以自定义权限，如systemserver相关的权限大多是在 framework-res.apk里定义的。
4. 具体相同的sharedUserId的package拥有相同的permission, 代码中使用的是Shared UID的permissions, 且该permissions是所有sharedUserId的合集。

# 九、参考
[android permissions](http://developer.android.com/intl/ja/guide/topics/security/permissions.html)
