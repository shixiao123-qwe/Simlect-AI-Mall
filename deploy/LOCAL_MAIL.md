# 本地邮箱验证码演示

仅在 UserApplication 的环境变量中追加：

```text
SIMLECT_MAIL_LOCAL_MOCK_ENABLED=true
```

重新编译并重启 UserApplication。点击注册页的“发送验证码”，完成滑块验证后，在 User 服务控制台搜索 `[本地邮件演示]`，找到对应邮箱的六位验证码并填写到注册页。

此模式不连接阿里云、不发送真实邮件，不需要 AccessKey。验证码仍随机生成，经原有控制器写入 Redis，有效期为 5 分钟；滑块校验、发送限流、注册和找回密码的验证码校验均保留。不要把任何示例数字当作固定验证码。同一邮箱发送失败后也可能需要等待 60 秒再试。

开关默认 false，不会随着 low-memory profile 自动开启。`SIMLECT_PRODUCTION_READY=true` 时禁止开启本地邮件演示，服务会拒绝启动。上线前关闭此开关并配置实际邮件服务。
