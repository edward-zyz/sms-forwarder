#!/usr/bin/env python3
"""生成 iPhone 万能版纯快捷指令未签名 .shortcut（binary plist）。

单个快捷指令自动识别飞书、企业微信、钉钉与自定义 HTTPS Webhook：
  1. 文本：Webhook 地址（导入问题 1）
  2. 文本：本机号码 / 备注（导入问题 2）
  3. 文本：统一消息正文（方便用户微调文案）
  4. 如果...否则...：按 Webhook 域名自动构造对应平台 JSON 词典
  5. 获取 URL 内容：POST 到第 1 步的 Webhook

用法：
    python3 ios/build-shortcut.py /tmp/sms-forwarder-ios.unsigned.shortcut
    shortcuts sign --mode anyone -i /tmp/sms-forwarder-ios.unsigned.shortcut -o ios/sms-forwarder-ios.shortcut

    # 或在 macOS 上一步生成并签名：
    python3 ios/build-shortcut.py --sign ios/sms-forwarder-ios.shortcut
"""

import argparse
import plistlib
import shutil
import subprocess
import tempfile
from pathlib import Path

WEBHOOK_ACTION_UUID = "6F3E9C10-5B2A-4D7E-9A11-000000000010"
DEVICE_ACTION_UUID = "6F3E9C10-5B2A-4D7E-9A11-000000000000"
MESSAGE_ACTION_UUID = "6F3E9C10-5B2A-4D7E-9A11-000000000011"

GROUP_FEISHU = "6F3E9C10-5B2A-4D7E-9A11-100000000001"
GROUP_WECOM = "6F3E9C10-5B2A-4D7E-9A11-100000000002"
GROUP_DINGTALK = "6F3E9C10-5B2A-4D7E-9A11-100000000003"

DICT_FEISHU_UUID = "6F3E9C10-5B2A-4D7E-9A11-200000000001"
DICT_WECOM_UUID = "6F3E9C10-5B2A-4D7E-9A11-200000000002"
DICT_DINGTALK_UUID = "6F3E9C10-5B2A-4D7E-9A11-200000000003"
DICT_CUSTOM_UUID = "6F3E9C10-5B2A-4D7E-9A11-200000000004"

PAYLOAD_RESULT_UUID = "6F3E9C10-5B2A-4D7E-9A11-300000000001"
HTTP_ACTION_UUID = "6F3E9C10-5B2A-4D7E-9A11-000000000002"

WF_CONDITION_CONTAINS = 99


def text_token(value: str) -> dict:
    return {
        "Value": {"string": value},
        "WFSerializationType": "WFTextTokenString",
    }


def action_output_text(output_uuid: str, output_name: str = "文本") -> dict:
    return {
        "Value": {
            "attachmentsByRange": {
                "{0, 1}": {
                    "OutputName": output_name,
                    "OutputUUID": output_uuid,
                    "Type": "ActionOutput",
                }
            },
            "string": "\ufffc",
        },
        "WFSerializationType": "WFTextTokenString",
    }


def action_output_attachment(output_uuid: str, output_name: str) -> dict:
    return {
        "Value": {
            "OutputName": output_name,
            "OutputUUID": output_uuid,
            "Type": "ActionOutput",
        },
        "WFSerializationType": "WFTextTokenAttachment",
    }


def nested_dict_value(items: list) -> dict:
    return {
        "Value": {
            "Value": {
                "WFDictionaryFieldValueItems": items,
            },
            "WFSerializationType": "WFDictionaryFieldValue",
        },
        "WFSerializationType": "WFDictionaryFieldValue",
    }


def dict_action(action_uuid: str, items: list) -> dict:
    return {
        "WFWorkflowActionIdentifier": "is.workflow.actions.dictionary",
        "WFWorkflowActionParameters": {
            "UUID": action_uuid,
            "WFItems": {
                "Value": {
                    "WFDictionaryFieldValueItems": items,
                },
                "WFSerializationType": "WFDictionaryFieldValue",
            },
        },
    }


def if_contains_action(group_id: str, input_uuid: str, needle: str) -> dict:
    return {
        "WFWorkflowActionIdentifier": "is.workflow.actions.conditional",
        "WFWorkflowActionParameters": {
            "GroupingIdentifier": group_id,
            "WFCondition": WF_CONDITION_CONTAINS,
            "WFConditionalActionString": needle,
            "WFControlFlowMode": 0,
            "WFInput": {
                "Type": "Variable",
                "Variable": action_output_attachment(input_uuid, "文本"),
            },
        },
    }


def otherwise_action(group_id: str) -> dict:
    return {
        "WFWorkflowActionIdentifier": "is.workflow.actions.conditional",
        "WFWorkflowActionParameters": {
            "GroupingIdentifier": group_id,
            "WFControlFlowMode": 1,
        },
    }


def endif_action(group_id: str, result_uuid: str | None = None) -> dict:
    params = {
        "GroupingIdentifier": group_id,
        "WFControlFlowMode": 2,
    }
    if result_uuid:
        params["UUID"] = result_uuid
    return {
        "WFWorkflowActionIdentifier": "is.workflow.actions.conditional",
        "WFWorkflowActionParameters": params,
    }


def build_shortcut() -> dict:
    # Action 2 正文模板索引核对（UTF-16 码元）：
    # 0..4: "短信转发\n" (5)
    # 5..9: "接收手机：" (5)
    # 10:   \ufffc -> 本机号码/备注（Action 1 输出）
    # 11:   "\n"
    # 12:   \ufffc -> 快捷指令输入（短信正文）
    message_template = {
        "Value": {
            "attachmentsByRange": {
                "{10, 1}": {
                    "OutputName": "文本",
                    "OutputUUID": DEVICE_ACTION_UUID,
                    "Type": "ActionOutput",
                },
                "{12, 1}": {"Type": "ExtensionInput"},
            },
            "string": "短信转发\n接收手机：\ufffc\n\ufffc",
        },
        "WFSerializationType": "WFTextTokenString",
    }

    msg_ref = action_output_text(MESSAGE_ACTION_UUID, "文本")

    feishu_items = [
        {"WFItemType": 0, "WFKey": text_token("msg_type"), "WFValue": text_token("text")},
        {
            "WFItemType": 1,
            "WFKey": text_token("content"),
            "WFValue": nested_dict_value([
                {"WFItemType": 0, "WFKey": text_token("text"), "WFValue": msg_ref},
            ]),
        },
    ]

    wecom_dingtalk_items = [
        {"WFItemType": 0, "WFKey": text_token("msgtype"), "WFValue": text_token("text")},
        {
            "WFItemType": 1,
            "WFKey": text_token("text"),
            "WFValue": nested_dict_value([
                {"WFItemType": 0, "WFKey": text_token("content"), "WFValue": msg_ref},
            ]),
        },
    ]

    custom_items = [
        {"WFItemType": 0, "WFKey": text_token("title"), "WFValue": text_token("短信转发")},
        {"WFItemType": 0, "WFKey": text_token("text"), "WFValue": msg_ref},
    ]

    headers_items = [
        {
            "WFItemType": 0,
            "WFKey": text_token("Content-Type"),
            "WFValue": text_token("application/json; charset=utf-8"),
        }
    ]

    actions = [
        # 0: Webhook 地址（导入问题 1）
        {
            "WFWorkflowActionIdentifier": "is.workflow.actions.gettext",
            "WFWorkflowActionParameters": {
                "UUID": WEBHOOK_ACTION_UUID,
                "WFTextActionText": "https://open.feishu.cn/open-apis/bot/v2/hook/请替换为你的机器人地址",
            },
        },
        # 1: 本机号码或门店备注（导入问题 2）
        {
            "WFWorkflowActionIdentifier": "is.workflow.actions.gettext",
            "WFWorkflowActionParameters": {
                "UUID": DEVICE_ACTION_UUID,
                "WFTextActionText": "iPhone",
            },
        },
        # 2: 统一消息正文模板（用户如需微调文案，只改这一步）
        {
            "WFWorkflowActionIdentifier": "is.workflow.actions.gettext",
            "WFWorkflowActionParameters": {
                "UUID": MESSAGE_ACTION_UUID,
                "WFTextActionText": message_template,
            },
        },
        # 3..15: 按 Webhook 域名自动选择对应平台 JSON 格式
        if_contains_action(GROUP_FEISHU, WEBHOOK_ACTION_UUID, "open.feishu.cn"),
        dict_action(DICT_FEISHU_UUID, feishu_items),
        otherwise_action(GROUP_FEISHU),
        if_contains_action(GROUP_WECOM, WEBHOOK_ACTION_UUID, "qyapi.weixin.qq.com"),
        dict_action(DICT_WECOM_UUID, wecom_dingtalk_items),
        otherwise_action(GROUP_WECOM),
        if_contains_action(GROUP_DINGTALK, WEBHOOK_ACTION_UUID, "oapi.dingtalk.com"),
        dict_action(DICT_DINGTALK_UUID, wecom_dingtalk_items),
        otherwise_action(GROUP_DINGTALK),
        dict_action(DICT_CUSTOM_UUID, custom_items),
        endif_action(GROUP_DINGTALK),
        endif_action(GROUP_WECOM),
        endif_action(GROUP_FEISHU, PAYLOAD_RESULT_UUID),
        # 16: POST 发送到 Webhook
        {
            "WFWorkflowActionIdentifier": "is.workflow.actions.downloadurl",
            "WFWorkflowActionParameters": {
                "UUID": HTTP_ACTION_UUID,
                "WFURL": action_output_text(WEBHOOK_ACTION_UUID, "文本"),
                "WFHTTPMethod": "POST",
                "WFHTTPBodyType": "File",
                "WFRequestVariable": action_output_attachment(PAYLOAD_RESULT_UUID, "如果的结果"),
                "ShowHeaders": True,
                "WFHTTPHeaders": {
                    "Value": {
                        "WFDictionaryFieldValueItems": headers_items,
                    },
                    "WFSerializationType": "WFDictionaryFieldValue",
                },
            },
        },
    ]

    return {
        "WFWorkflowActions": actions,
        "WFWorkflowClientVersion": "2302.0.4",
        "WFWorkflowHasOutputFallback": False,
        "WFWorkflowHasShortcutInputVariables": True,
        "WFWorkflowIcon": {
            "WFWorkflowIconGlyphNumber": 59761,
            "WFWorkflowIconStartColor": 4282601983,
        },
        "WFWorkflowImportQuestions": [
            {
                "ActionIndex": 0,
                "Category": "Parameter",
                "DefaultValue": "https://",
                "ParameterKey": "WFTextActionText",
                "Text": "请粘贴你的群机器人或自定义 HTTPS Webhook 地址（自动识别飞书 / 企业微信 / 钉钉 / 自定义；飞书与钉钉请勾选关键词“短信转发”）：",
            },
            {
                "ActionIndex": 1,
                "Category": "Parameter",
                "DefaultValue": "iPhone",
                "ParameterKey": "WFTextActionText",
                "Text": "本机号码或门店备注（例如 138****8888 或 浦东店·美团号，多台手机转发到同一群时用于区分来源）：",
            },
        ],
        "WFWorkflowInputContentItemClasses": [
            "WFStringContentItem",
        ],
        "WFWorkflowMinimumClientVersion": 900,
        "WFWorkflowMinimumClientVersionString": "900",
        "WFWorkflowOutputContentItemClasses": [],
        "WFWorkflowTypes": [],
    }


def write_unsigned(output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("wb") as f:
        plistlib.dump(build_shortcut(), f, fmt=plistlib.FMT_BINARY)
    print(f"Generated unsigned shortcut: {output} ({output.stat().st_size} bytes)")


def main() -> int:
    ap = argparse.ArgumentParser(description="生成 iPhone 万能版纯快捷指令 .shortcut")
    ap.add_argument("output", nargs="?", help="未签名 .shortcut 输出路径")
    ap.add_argument("--sign", metavar="OUTPUT", help="在 macOS 上直接生成并签名输出到指定路径")
    args = ap.parse_args()

    if args.sign:
        dest = Path(args.sign)
        with tempfile.TemporaryDirectory() as tmp:
            unsigned = Path(tmp) / "unsigned.shortcut"
            signed = Path(tmp) / dest.name
            write_unsigned(unsigned)
            subprocess.run(
                ["shortcuts", "sign", "--mode", "anyone", "-i", str(unsigned), "-o", str(signed)],
                check=True,
            )
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(signed, dest)
            dest.chmod(0o644)
        print(f"Signed shortcut: {dest} ({dest.stat().st_size} bytes)")
        return 0

    if not args.output:
        ap.error("请指定输出路径，或使用 --sign <输出路径>")
    write_unsigned(Path(args.output))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
