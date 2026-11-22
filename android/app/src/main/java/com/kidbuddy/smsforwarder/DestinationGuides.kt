package com.kidbuddy.smsforwarder

/** 单个配置步骤。 */
data class GuideStep(
    val title: String,
    val detail: String,
)

/** 单个转发渠道的完整配置教程（纯 Kotlin 数据，不依赖 Android）。 */
data class DestinationGuide(
    val type: DestinationType,
    val title: String,
    val summary: String,
    val scenario: String,
    val steps: List<GuideStep>,
    val securityNote: String,
    val pitfalls: List<String>,
    val webhookExample: String,
)

/** 内置 4 平台渠道详细配置教程。 */
object DestinationGuides {
    private val guides: Map<DestinationType, DestinationGuide> = listOf(
        DestinationGuide(
            type = DestinationType.FEISHU,
            title = "飞书群机器人配置教程",
            summary = "支持签名校验与自定义关键词，适合团队群实时收码。",
            scenario = "适合日常使用飞书协同的团队，把店铺/业务验证码或告警短信推送到指定私密飞书群。",
            steps = listOf(
                GuideStep(
                    title = "准备飞书接收群",
                    detail = "在飞书新建一个仅包含相关成员的私密群（或打开已有接收群），确认群内成员均可查看短信原文与验证码。",
                ),
                GuideStep(
                    title = "添加自定义机器人",
                    detail = "打开目标群右上角「设置」→「群机器人」→「添加机器人」→ 选择「自定义机器人」，填写名称（例如「短信转发」）并完成创建。若无入口，请联系群主或管理员开启添加机器人权限。",
                ),
                GuideStep(
                    title = "配置安全设置（推荐开启签名校验）",
                    detail = "在机器人设置页勾选「签名校验」并复制生成的签名密钥；如勾选「自定义关键词」，请填入「短信转发」（本 App 推送均包含这四个字）。切勿开启「IP 白名单」。",
                ),
                GuideStep(
                    title = "复制 Webhook 地址并填入本页",
                    detail = "复制飞书提供的完整 Webhook 地址粘贴到「Webhook 地址」输入框；若开启了签名校验，请同时将签名密钥填入「签名密钥」输入框，保存后可在列表发送测试消息验证。",
                ),
            ),
            securityNote = "本 App 支持飞书 HMAC-SHA256 签名校验（填写签名密钥后自动按秒级时间戳计算 sign）与「短信转发」关键词校验。建议开启「签名校验」，避免裸地址被他人恶意调用。",
            pitfalls = listOf(
                "不要启用「IP 白名单」：手机移动网络或切换 Wi-Fi 时出口 IP 会变化，开启后会导致推送被拦截。",
                "若启用了「自定义关键词」但未加入「短信转发」，飞书服务端会返回关键词校验失败。",
                "请勿把 Webhook 地址和签名密钥截图发到外部群，泄露后应立即在飞书群移除旧机器人并重建。",
            ),
            webhookExample = "https://open.feishu.cn/open-apis/bot/v2/hook/example-feishu-hook-id",
        ),
        DestinationGuide(
            type = DestinationType.WECOM,
            title = "企业微信群机器人配置教程",
            summary = "配置最简，复制含 key 的群机器人地址即可使用。",
            scenario = "适合使用企业微信办公的门店与运营团队，将短信通知直接推入企业微信内部工作群。",
            steps = listOf(
                GuideStep(
                    title = "打开企业微信内部群",
                    detail = "在企业微信电脑端或手机端打开用于接收短信的内部群。含外部联系人或普通微信用户的外部群通常不支持添加自定义群机器人。",
                ),
                GuideStep(
                    title = "创建群机器人",
                    detail = "点击群右上角「…」→「群机器人」→「添加机器人」→「新创建一个机器人」，设置机器人名称（例如「短信转发」）并确认添加。",
                ),
                GuideStep(
                    title = "复制完整 Webhook 地址",
                    detail = "在机器人详情页点击「复制地址」，获取包含 ?key=... 参数的完整 HTTPS Webhook 地址。",
                ),
                GuideStep(
                    title = "粘贴保存并验证",
                    detail = "在本页选择「企业微信」，将地址粘贴到「Webhook 地址」并保存。企业微信群机器人无需签名密钥，本页会自动隐藏密钥输入框。",
                ),
            ),
            securityNote = "企业微信群机器人通过地址中的 key 鉴权，不支持额外加签或关键词设置。请仅将机器人添加到可信内部群，并妥善保管含 key 的完整地址。",
            pitfalls = listOf(
                "仅企业微信内部群支持群机器人；若群设置中找不到「群机器人」入口，请检查是否为外部群或联系管理员确认权限。",
                "请复制完整地址并保留 ?key=... 参数，不要截断或自行拼接其他查询参数。",
                "若机器人在群内被重置或移除，原 key 会立即失效，需要重新获取新地址并在此更新。",
            ),
            webhookExample = "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=example-wecom-key-1234",
        ),
        DestinationGuide(
            type = DestinationType.DINGTALK,
            title = "钉钉群机器人配置教程",
            summary = "支持加签（SEC 密钥）或关键词校验，适合稳定推送通知。",
            scenario = "适合使用钉钉办公、值班告警或店铺运营的团队，通过自定义群机器人接收转发短信。",
            steps = listOf(
                GuideStep(
                    title = "进入钉钉群机器人管理",
                    detail = "在钉钉电脑端打开接收群，点击右上角「群设置」→「机器人」→「添加机器人」→ 选择「自定义」机器人。",
                ),
                GuideStep(
                    title = "设置安全校验（推荐勾选加签）",
                    detail = "钉钉要求至少勾选一种安全设置：推荐勾选「加签」并复制以 SEC 开头的密钥；若勾选「自定义关键词」，请填入「短信转发」。不建议仅使用「IP 地址段」。",
                ),
                GuideStep(
                    title = "完成创建并复制 Webhook",
                    detail = "阅读并同意服务条款后点击完成，复制生成的完整 Webhook 地址（包含 ?access_token=... 参数）。",
                ),
                GuideStep(
                    title = "填入地址与加签密钥",
                    detail = "在本页选择「钉钉」并粘贴 Webhook 地址；如果机器人启用了「加签」，务必把 SEC 开头的密钥填入「签名密钥」，保存后发测试消息确认。",
                ),
            ),
            securityNote = "启用「加签」后，本 App 会使用当前毫秒级时间戳与 SEC 密钥计算 HMAC-SHA256 签名并附带在请求中；请保持手机系统日期与时间为自动校准。",
            pitfalls = listOf(
                "机器人开启了「加签」却未填写 SEC 密钥，或填了密钥但机器人实际未开启加签，都会导致钉钉拒收。",
                "手机系统时间偏差过大时加签会校验失败，请在手机系统设置中开启「自动确定日期和时间」。",
                "钉钉单个群机器人有每分钟约 20 条的发送频率限制，请配合规则过滤只转发必要短信。",
            ),
            webhookExample = "https://oapi.dingtalk.com/robot/send?access_token=example-dingtalk-token-1234",
        ),
        DestinationGuide(
            type = DestinationType.CUSTOM,
            title = "自定义 HTTPS Webhook 配置教程",
            summary = "对接自建服务或自动化平台，支持标准 JSON 与请求头验签。",
            scenario = "适合需要将短信接入自建后台、工单系统或自动化工作流（如中转服务、内部告警网关）的团队与开发者。",
            steps = listOf(
                GuideStep(
                    title = "准备支持 POST 的 HTTPS 接口",
                    detail = "在服务端或自动化平台部署一个可公网访问的 HTTPS POST 接口。出于安全考虑，本 App 拒绝明文 http:// 地址，且禁止 URL 中附带账号密码或 # 锚点。",
                ),
                GuideStep(
                    title = "解析标准 JSON 请求体",
                    detail = "接收端将收到 application/json 请求体：{\"title\":\"短信转发\",\"text\":\"...\",\"timestamp\":秒级时间戳}，服务端返回 HTTP 2xx（如 200、204）即视为发送成功。",
                ),
                GuideStep(
                    title = "（可选）接入请求头 HMAC 签名验证",
                    detail = "若在本页填写了「签名密钥」，请求头会附带 X-Webhook-Timestamp（秒级时间戳）和 X-Webhook-Signature（对「timestamp\\n原始JSON体」做 HMAC-SHA256 后 Base64 编码）。",
                ),
                GuideStep(
                    title = "填写地址并测试连通性",
                    detail = "将完整的 https://... 地址填入「Webhook 地址」，按需填写签名密钥并保存，随后在目的地列表点击「发测试消息」验证服务端回执。",
                ),
            ),
            securityNote = "公网接口强烈建议配置「签名密钥」，并在服务端同时校验 X-Webhook-Timestamp 时效（例如允许 ±300 秒内）与 X-Webhook-Signature，防止未授权请求与重放攻击。",
            pitfalls = listOf(
                "仅支持受系统信任证书的 HTTPS 地址，自签名证书或 http:// 明文接口会发送失败。",
                "计算 X-Webhook-Signature 时需对「秒级时间戳 + 换行符(\\n) + 完整原始 JSON 字符串」签名，而不是只签 text 内容。",
                "服务端请及时返回 2xx 状态码，避免请求超时引发后台队列重试造成重复投递。",
            ),
            webhookExample = "https://example.com/webhook/sms",
        ),
    ).associateBy { it.type }

    fun all(): List<DestinationGuide> = DestinationType.entries.map { forType(it) }

    fun forType(type: DestinationType): DestinationGuide =
        guides[type] ?: error("缺少 ${type.displayName} 的配置教程")
}
