# jenkins-atom-aws-codedeploy

从 S3 修订包发起一次 CodeDeploy 部署，并可等待它结束。

aws-java-sdk-codedeploy 插件通过 SDK 做同一件事。这里是一个**签过名的 JSON 请求 + 轮询**：
自己实现 SigV4（HMAC 链），不引入 AWS SDK——整个原子就一次调用，SDK 的体积换不来什么。

失败要说清楚：

- AWS 拒绝时，把它的错误原文带出来（`HTTP 400` + 错误体），不吞成 "调用失败"
- 部署最终 `FAILED` 时，报状态并带上 `failedInstances/succeededInstances` 计数
- `wait=false` 时只返回 `deploymentId`，适合触发即走

```bash
mvn verify
```

`src/test/java/atom/AwsCodeDeployIT.java` 会起一个**自己复算 SigV4 签名**的假端点（`tools/fake_aws.py`）：签名不对就 403。
所以"跑通"这件事本身就证明了签名链是对的，而不是端点不验签。
