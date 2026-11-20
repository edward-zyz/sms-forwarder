#!/usr/bin/env python3
"""生成「短信转飞书」iPhone 纯快捷指令未签名 .shortcut（binary plist）。

用法：
    python3 ios/build-shortcut.py /tmp/sms-forwarder-ios.unsigned.shortcut
    shortcuts sign --mode anyone -i /tmp/sms-forwarder-ios.unsigned.shortcut -o ios/sms-forwarder-ios.shortcut
"""

import plistlib
import sys
from pathlib import Path

DICT_ACTION_UUID = "6F3E9C10-5B2A-4D7E-9A11-000000000001"
HTTP_ACTION_UUID = "6F3E9C10-5B2A-4D7E-9A11-000000000002"


def text_token(value: str) -> dict:
    return {
        "Value": {"string": value},
        "WFSerializationType": "WFTextTokenString",
    }


def build_shortcut() -> dict:
    # "短信转发\n" 为 5 个 UTF-16 码元，后接对象替换字符 \ufffc 引用快捷指令输入（短信正文）
    sms_text_value = {
        "Value": {
            "attachmentsByRange": {
                "{5, 1}": {"Type": "ExtensionInput"},
            },
            "string": "短信转发\n\ufffc",
        },
        "WFSerializationType": "WFTextTokenString",
    }

    content_dict_items = [
        {
            "WFItemType": 0,  # Text
            "WFKey": text_token("text"),
            "WFValue": sms_text_value,
        }
    ]

    payload_dict_items = [
        {
            "WFItemType": 0,  # Text
            "WFKey": text_token("msg_type"),
            "WFValue": text_token("text"),
        },
        {
            "WFItemType": 1,  # Dictionary
            "WFKey": text_token("content"),
            "WFValue": {
                "Value": {
                    "Value": {
                        "WFDictionaryFieldValueItems": content_dict_items,
                    },
                    "WFSerializationType": "WFDictionaryFieldValue",
                },
                "WFSerializationType": "WFDictionaryFieldValue",
            },
        },
    ]

    headers_items = [
        {
            "WFItemType": 0,
            "WFKey": text_token("Content-Type"),
            "WFValue": text_token("application/json; charset=utf-8"),
        }
    ]

    actions = [
        {
            "WFWorkflowActionIdentifier": "is.workflow.actions.dictionary",
            "WFWorkflowActionParameters": {
                "UUID": DICT_ACTION_UUID,
                "WFItems": {
                    "Value": {
                        "WFDictionaryFieldValueItems": payload_dict_items,
                    },
                    "WFSerializationType": "WFDictionaryFieldValue",
                },
            },
        },
        {
            "WFWorkflowActionIdentifier": "is.workflow.actions.downloadurl",
            "WFWorkflowActionParameters": {
                "UUID": HTTP_ACTION_UUID,
                "WFURL": "https://open.feishu.cn/open-apis/bot/v2/hook/请替换为你的机器人地址",
                "WFHTTPMethod": "POST",
                "WFHTTPBodyType": "File",
                "WFRequestVariable": {
                    "Value": {
                        "OutputName": "词典",
                        "OutputUUID": DICT_ACTION_UUID,
                        "Type": "ActionOutput",
                    },
                    "WFSerializationType": "WFTextTokenAttachment",
                },
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
                "ActionIndex": 1,
                "Category": "Parameter",
                "DefaultValue": "https://open.feishu.cn/open-apis/bot/v2/hook/",
                "ParameterKey": "WFURL",
                "Text": "请粘贴你的飞书群机器人 Webhook 地址（需在飞书群机器人安全设置中勾选自定义关键词“短信转发”）：",
            }
        ],
        "WFWorkflowInputContentItemClasses": [
            "WFStringContentItem",
        ],
        "WFWorkflowMinimumClientVersion": 900,
        "WFWorkflowMinimumClientVersionString": "900",
        "WFWorkflowOutputContentItemClasses": [],
        "WFWorkflowTypes": [],
    }


def main() -> int:
    if len(sys.argv) != 2:
        print(f"用法: {sys.argv[0]} <output.shortcut>", file=sys.stderr)
        return 1
    output = Path(sys.argv[1])
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("wb") as f:
        plistlib.dump(build_shortcut(), f, fmt=plistlib.FMT_BINARY)
    print(f"Generated unsigned shortcut: {output} ({output.stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
