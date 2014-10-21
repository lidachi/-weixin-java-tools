/*
 * KINGSTAR MEDIA SOLUTIONS Co.,LTD. Copyright c 2005-2013. All rights reserved.
 *
 * This source code is the property of KINGSTAR MEDIA SOLUTIONS LTD. It is intended
 * only for the use of KINGSTAR MEDIA application development. Reengineering, reproduction
 * arose from modification of the original source, or other redistribution of this source
 * is not permitted without written permission of the KINGSTAR MEDIA SOLUTIONS LTD.
 */
package me.chanjar.weixin.enterprise.util.json;

import java.lang.reflect.Type;

import me.chanjar.weixin.enterprise.api.WxCpConsts;
import me.chanjar.weixin.enterprise.bean.WxCpMessage;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import org.apache.commons.lang3.StringUtils;

/**
 * 
 * @author Daniel Qian
 *
 */
public class WxCpMessageGsonAdapter implements JsonSerializer<WxCpMessage> {

  public JsonElement serialize(WxCpMessage message, Type typeOfSrc, JsonSerializationContext context) {
    JsonObject messageJson = new JsonObject();
    messageJson.addProperty("agentid", message.getAgentId());
    messageJson.addProperty("msgtype", message.getMsgType());

    if (StringUtils.isNotBlank(message.getToUser())) {
      messageJson.addProperty("touser", message.getToUser());
    }
    if (StringUtils.isNotBlank(message.getToParty())) {
      messageJson.addProperty("toparty", message.getToUser());
    }
    if (StringUtils.isNotBlank(message.getToTag())) {
      messageJson.addProperty("totag", message.getToUser());
    }
    if (WxCpConsts.CUSTOM_MSG_TEXT.equals(message.getMsgType())) {
      JsonObject text = new JsonObject();
      text.addProperty("content", message.getContent());
      messageJson.add("text", text);
    }

    if (WxCpConsts.CUSTOM_MSG_IMAGE.equals(message.getMsgType())) {
      JsonObject image = new JsonObject();
      image.addProperty("media_id", message.getMediaId());
      messageJson.add("image", image);
    }

    if (WxCpConsts.CUSTOM_MSG_FILE.equals(message.getMsgType())) {
      JsonObject image = new JsonObject();
      image.addProperty("media_id", message.getMediaId());
      messageJson.add("file", image);
    }

    if (WxCpConsts.CUSTOM_MSG_VOICE.equals(message.getMsgType())) {
      JsonObject voice = new JsonObject();
      voice.addProperty("media_id", message.getMediaId());
      messageJson.add("voice", voice);
    }

    if (WxCpConsts.CUSTOM_MSG_VIDEO.equals(message.getMsgType())) {
      JsonObject video = new JsonObject();
      video.addProperty("media_id", message.getMediaId());
      video.addProperty("thumb_media_id", message.getThumbMediaId());
      video.addProperty("title", message.getTitle());
      video.addProperty("description", message.getDescription());
      messageJson.add("video", video);
    }

    if (WxCpConsts.CUSTOM_MSG_NEWS.equals(message.getMsgType())) {
      JsonArray articleJsonArray = new JsonArray();
      for (WxCpMessage.WxArticle article : message.getArticles()) {
        JsonObject articleJson = new JsonObject();
        articleJson.addProperty("title", article.getTitle());
        articleJson.addProperty("description", article.getDescription());
        articleJson.addProperty("url", article.getUrl());
        articleJson.addProperty("picurl", article.getPicUrl());
        articleJsonArray.add(articleJson);
      }
      messageJson.add("articles", articleJsonArray);
    }
    
    return messageJson;
  }

}
