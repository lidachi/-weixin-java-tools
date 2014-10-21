package me.chanjar.weixin.enterprise.bean.messagebuilder;

import me.chanjar.weixin.enterprise.api.WxCpConsts;
import me.chanjar.weixin.enterprise.bean.WxCpMessage;

/**
 * 语音消息builder
 * <pre>
 * 用法: WxCustomMessage m = WxCustomMessage.VOICE().mediaId(...).toUser(...).build();
 * </pre>
 * @author Daniel Qian
 *
 */
public final class VoiceBuilder extends BaseBuilder<VoiceBuilder> {
  private String mediaId;

  public VoiceBuilder() {
    this.msgType = WxCpConsts.CUSTOM_MSG_VOICE;
  }

  public VoiceBuilder mediaId(String media_id) {
    this.mediaId = media_id;
    return this;
  }

  public WxCpMessage build() {
    WxCpMessage m = super.build();
    m.setMediaId(this.mediaId);
    return m;
  }
}
