package me.chanjar.weixin.open.api.impl;

import cn.binarywang.wx.miniapp.bean.WxMaJscode2SessionResult;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.chanjar.weixin.common.api.WxConsts;
import me.chanjar.weixin.common.error.WxError;
import me.chanjar.weixin.common.error.WxErrorException;
import me.chanjar.weixin.common.error.WxRuntimeException;
import me.chanjar.weixin.common.util.crypto.SHA1;
import me.chanjar.weixin.common.util.http.URIUtil;
import me.chanjar.weixin.common.util.json.GsonParser;
import me.chanjar.weixin.common.util.json.WxGsonBuilder;
import me.chanjar.weixin.mp.api.WxMpService;
import me.chanjar.weixin.common.bean.oauth2.WxOAuth2AccessToken;
import me.chanjar.weixin.open.api.*;
import me.chanjar.weixin.open.bean.*;
import me.chanjar.weixin.open.bean.auth.WxOpenAuthorizationInfo;
import me.chanjar.weixin.open.bean.message.WxOpenXmlMessage;
import me.chanjar.weixin.open.bean.result.*;
import me.chanjar.weixin.open.util.json.WxOpenGsonBuilder;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;

/**
 * @author <a href="https://github.com/007gzs">007</a>
 */
@Slf4j
@AllArgsConstructor
public class WxOpenComponentServiceImpl implements WxOpenComponentService {

  private static final Map<String, WxOpenMaService> WX_OPEN_MA_SERVICE_MAP = new ConcurrentHashMap<>();
  private static final Map<String, WxOpenMpService> WX_OPEN_MP_SERVICE_MAP = new ConcurrentHashMap<>();
  private static final Map<String, WxOpenFastMaService> WX_OPEN_FAST_MA_SERVICE_MAP = new ConcurrentHashMap<>();

  private final WxOpenService wxOpenService;

  @Override
  public WxOpenMpService getWxMpServiceByAppid(String appId) {
    WxOpenMpService wxMpService = WX_OPEN_MP_SERVICE_MAP.get(appId);
    if (wxMpService == null) {
      synchronized (WX_OPEN_MP_SERVICE_MAP) {
        wxMpService = WX_OPEN_MP_SERVICE_MAP.get(appId);
        if (wxMpService == null) {
          wxMpService = new WxOpenMpServiceImpl(this, appId, getWxOpenConfigStorage().getWxMpConfigStorage(appId));

          WX_OPEN_MP_SERVICE_MAP.put(appId, wxMpService);
        }
      }
    }
    return wxMpService;
  }

  @Override
  public WxOpenMaService getWxMaServiceByAppid(String appId) {
    WxOpenMaService wxOpenMaService = WX_OPEN_MA_SERVICE_MAP.get(appId);
    if (wxOpenMaService == null) {
      synchronized (WX_OPEN_MA_SERVICE_MAP) {
        wxOpenMaService = WX_OPEN_MA_SERVICE_MAP.get(appId);
        if (wxOpenMaService == null) {
          wxOpenMaService = new WxOpenMaServiceImpl(this, appId, getWxOpenConfigStorage().getWxMaConfig(appId));
          WX_OPEN_MA_SERVICE_MAP.put(appId, wxOpenMaService);
        }
      }
    }
    return wxOpenMaService;
  }

  @Override
  public WxOpenFastMaService getWxFastMaServiceByAppid(String appId) {
    WxOpenFastMaService fastMaService = WX_OPEN_FAST_MA_SERVICE_MAP.get(appId);
    if (fastMaService == null) {
      synchronized (WX_OPEN_FAST_MA_SERVICE_MAP) {
        fastMaService = WX_OPEN_FAST_MA_SERVICE_MAP.get(appId);
        if (fastMaService == null) {
          fastMaService = new WxOpenFastMaServiceImpl(this, appId, getWxOpenConfigStorage().getWxMaConfig(appId));
          WX_OPEN_FAST_MA_SERVICE_MAP.put(appId, fastMaService);
        }
      }
    }
    return fastMaService;
  }

  public WxOpenService getWxOpenService() {
    return wxOpenService;
  }

  @Override
  public WxOpenConfigStorage getWxOpenConfigStorage() {
    return wxOpenService.getWxOpenConfigStorage();
  }

  @Override
  public boolean checkSignature(String timestamp, String nonce, String signature) {
    try {
      return SHA1.gen(getWxOpenConfigStorage().getComponentToken(), timestamp, nonce)
        .equals(signature);
    } catch (Exception e) {
      this.log.error("Checking signature failed, and the reason is :" + e.getMessage());
      return false;
    }
  }

  @Override
  public String getComponentAccessToken(boolean forceRefresh) throws WxErrorException {
    final WxOpenConfigStorage config = this.getWxOpenConfigStorage();
    if (!config.isComponentAccessTokenExpired() && !forceRefresh) {
      return config.getComponentAccessToken();
    }
    Lock lock = config.getComponentAccessTokenLock();
    lock.lock();
    try {
      if (!config.isComponentAccessTokenExpired() && !forceRefresh) {
        return config.getComponentAccessToken();
      }
      JsonObject jsonObject = new JsonObject();
      jsonObject.addProperty("component_appid", getWxOpenConfigStorage().getComponentAppId());
      jsonObject.addProperty("component_appsecret", getWxOpenConfigStorage().getComponentAppSecret());
      jsonObject.addProperty("component_verify_ticket", getWxOpenConfigStorage().getComponentVerifyTicket());

      String responseContent = this.getWxOpenService().post(API_COMPONENT_TOKEN_URL, jsonObject.toString());
      WxOpenComponentAccessToken componentAccessToken = WxOpenComponentAccessToken.fromJson(responseContent);
      config.updateComponentAccessToken(componentAccessToken);
      return config.getComponentAccessToken();
    } finally {
      lock.unlock();
    }
  }

  @Override
  public String post(String uri, String postData) throws WxErrorException {
    return post(uri, postData, "component_access_token");
  }

  @Override
  public String post(String uri, String postData, String accessTokenKey) throws WxErrorException {
    String componentAccessToken = getComponentAccessToken(false);
    String uriWithComponentAccessToken = uri + (uri.contains("?") ? "&" : "?") + accessTokenKey + "=" + componentAccessToken;
    try {
      return getWxOpenService().post(uriWithComponentAccessToken, postData);
    } catch (WxErrorException e) {
      WxError error = e.getError();
      if (WxConsts.ACCESS_TOKEN_ERROR_CODES.contains(error.getErrorCode())) {
        // ????????????access token????????????????????????????????????????????????access token
        Lock lock = this.getWxOpenConfigStorage().getComponentAccessTokenLock();
        lock.lock();
        try {
          if (StringUtils.equals(componentAccessToken, this.getWxOpenConfigStorage().getComponentAccessToken())) {
            this.getWxOpenConfigStorage().expireComponentAccessToken();
          }
        } catch (Exception ex) {
          this.getWxOpenConfigStorage().expireComponentAccessToken();
        } finally {
          lock.unlock();
        }

        if (this.getWxOpenConfigStorage().autoRefreshToken()) {
          log.warn("????????????????????????access_token??????????????????{}??????????????????{}", error.getErrorCode(), error.getErrorMsg());
          return this.post(uri, postData, accessTokenKey);
        }
      }
      if (error.getErrorCode() != 0) {
        throw new WxErrorException(error, e);
      }
      return null;
    }
  }

  @Override
  public String get(String uri) throws WxErrorException {
    return get(uri, "component_access_token");
  }

  @Override
  public String get(String uri, String accessTokenKey) throws WxErrorException {
    String componentAccessToken = getComponentAccessToken(false);
    String uriWithComponentAccessToken = uri + (uri.contains("?") ? "&" : "?") + accessTokenKey + "=" + componentAccessToken;
    try {
      return getWxOpenService().get(uriWithComponentAccessToken, null);
    } catch (WxErrorException e) {
      WxError error = e.getError();
      if (WxConsts.ACCESS_TOKEN_ERROR_CODES.contains(error.getErrorCode())) {
        // ????????????wxMpConfigStorage??????access token???????????????????????????????????????????????????access token
        Lock lock = this.getWxOpenConfigStorage().getComponentAccessTokenLock();
        lock.lock();
        try {
          if (StringUtils.equals(componentAccessToken, this.getWxOpenConfigStorage().getComponentAccessToken())) {
            this.getWxOpenConfigStorage().expireComponentAccessToken();
          }
        } catch (Exception ex) {
          this.getWxOpenConfigStorage().expireComponentAccessToken();
        } finally {
          lock.unlock();
        }
        if (this.getWxOpenConfigStorage().autoRefreshToken()) {
          log.warn("????????????????????????access_token??????????????????{}??????????????????{}", error.getErrorCode(), error.getErrorMsg());
          return this.get(uri, accessTokenKey);
        }
      }
      if (error.getErrorCode() != 0) {
        throw new WxErrorException(error, e);
      }
      return null;
    }
  }

  @Override
  public String getPreAuthUrl(String redirectUri) throws WxErrorException {
    return getPreAuthUrl(redirectUri, null, null);
  }

  @Override
  public String getPreAuthUrl(String redirectUri, String authType, String bizAppid) throws WxErrorException {
    return createPreAuthUrl(redirectUri, authType, bizAppid, false);
  }

  @Override
  public String getMobilePreAuthUrl(String redirectUri) throws WxErrorException {
    return getMobilePreAuthUrl(redirectUri, null, null);
  }

  @Override
  public String getMobilePreAuthUrl(String redirectUri, String authType, String bizAppid) throws WxErrorException {
    return createPreAuthUrl(redirectUri, authType, bizAppid, true);
  }

  /**
   * ?????????????????????
   */
  private String createPreAuthUrl(String redirectUri, String authType, String bizAppid, boolean isMobile) throws WxErrorException {
    JsonObject jsonObject = new JsonObject();
    jsonObject.addProperty("component_appid", getWxOpenConfigStorage().getComponentAppId());
    String responseContent = post(API_CREATE_PREAUTHCODE_URL, jsonObject.toString());
    jsonObject = WxGsonBuilder.create().fromJson(responseContent, JsonObject.class);

    String preAuthUrlStr = String.format((isMobile ? COMPONENT_MOBILE_LOGIN_PAGE_URL : COMPONENT_LOGIN_PAGE_URL),
      getWxOpenConfigStorage().getComponentAppId(),
      jsonObject.get("pre_auth_code").getAsString(),
      URIUtil.encodeURIComponent(redirectUri));
    if (StringUtils.isNotEmpty(authType)) {
      preAuthUrlStr = preAuthUrlStr.replace("&auth_type=xxx", "&auth_type=" + authType);
    } else {
      preAuthUrlStr = preAuthUrlStr.replace("&auth_type=xxx", "");
    }
    if (StringUtils.isNotEmpty(bizAppid)) {
      preAuthUrlStr = preAuthUrlStr.replace("&biz_appid=xxx", "&biz_appid=" + bizAppid);
    } else {
      preAuthUrlStr = preAuthUrlStr.replace("&biz_appid=xxx", "");
    }
    return preAuthUrlStr;
  }


  @Override
  public String route(final WxOpenXmlMessage wxMessage) throws WxErrorException {
    if (wxMessage == null) {
      throw new NullPointerException("message is empty");
    }
    if (StringUtils.equalsIgnoreCase(wxMessage.getInfoType(), "component_verify_ticket")) {
      getWxOpenConfigStorage().setComponentVerifyTicket(wxMessage.getComponentVerifyTicket());
      return "success";
    }
    //?????????????????????
    if (StringUtils.equalsAnyIgnoreCase(wxMessage.getInfoType(), "authorized", "updateauthorized")) {
      WxOpenQueryAuthResult queryAuth = wxOpenService.getWxOpenComponentService().getQueryAuth(wxMessage.getAuthorizationCode());
      if (queryAuth == null || queryAuth.getAuthorizationInfo() == null || queryAuth.getAuthorizationInfo().getAuthorizerAppid() == null) {
        throw new NullPointerException("getQueryAuth");
      }
      return "success";
    }
    //?????????????????????
    if (StringUtils.equalsIgnoreCase(wxMessage.getInfoType(), "notify_third_fasteregister") && wxMessage.getStatus() == 0) {
      WxOpenQueryAuthResult queryAuth = wxOpenService.getWxOpenComponentService().getQueryAuth(wxMessage.getAuthCode());
      if (queryAuth == null || queryAuth.getAuthorizationInfo() == null || queryAuth.getAuthorizationInfo().getAuthorizerAppid() == null) {
        throw new NullPointerException("getQueryAuth");
      }
      return "success";
    }
    return "";
  }

  @Override
  public WxOpenQueryAuthResult getQueryAuth(String authorizationCode) throws WxErrorException {
    JsonObject jsonObject = new JsonObject();
    jsonObject.addProperty("component_appid", getWxOpenConfigStorage().getComponentAppId());
    jsonObject.addProperty("authorization_code", authorizationCode);
    String responseContent = post(API_QUERY_AUTH_URL, jsonObject.toString());
    WxOpenQueryAuthResult queryAuth = WxOpenGsonBuilder.create().fromJson(responseContent, WxOpenQueryAuthResult.class);
    if (queryAuth == null || queryAuth.getAuthorizationInfo() == null) {
      return queryAuth;
    }
    WxOpenAuthorizationInfo authorizationInfo = queryAuth.getAuthorizationInfo();
    if (authorizationInfo.getAuthorizerAccessToken() != null) {
      getWxOpenConfigStorage().updateAuthorizerAccessToken(authorizationInfo.getAuthorizerAppid(),
        authorizationInfo.getAuthorizerAccessToken(), authorizationInfo.getExpiresIn());
    }
    if (authorizationInfo.getAuthorizerRefreshToken() != null) {
      getWxOpenConfigStorage().updateAuthorizerRefreshToken(authorizationInfo.getAuthorizerAppid(), authorizationInfo.getAuthorizerRefreshToken());
    }
    return queryAuth;
  }

  @Override
  public WxOpenAuthorizerInfoResult getAuthorizerInfo(String authorizerAppid) throws WxErrorException {
    JsonObject jsonObject = new JsonObject();
    jsonObject.addProperty("component_appid", getWxOpenConfigStorage().getComponentAppId());
    jsonObject.addProperty("authorizer_appid", authorizerAppid);
    String responseContent = post(API_GET_AUTHORIZER_INFO_URL, jsonObject.toString());
    return WxOpenGsonBuilder.create().fromJson(responseContent, WxOpenAuthorizerInfoResult.class);
  }

  @Override
  public WxOpenAuthorizerListResult getAuthorizerList(int begin, int len) throws WxErrorException {
    begin = Math.max(begin, 0);
    len = len == 0 ? 10 : len;
    JsonObject jsonObject = new JsonObject();
    jsonObject.addProperty("component_appid", getWxOpenConfigStorage().getComponentAppId());
    jsonObject.addProperty("offset", begin);
    jsonObject.addProperty("count", len);
    String responseContent = post(API_GET_AUTHORIZER_LIST, jsonObject.toString());
    WxOpenAuthorizerListResult ret = WxOpenGsonBuilder.create().fromJson(responseContent, WxOpenAuthorizerListResult.class);
    if (ret != null && ret.getList() != null) {
      for (Map<String, String> data : ret.getList()) {
        String authorizerAppid = data.get("authorizer_appid");
        String refreshToken = data.get("refresh_token");
        if (authorizerAppid != null && refreshToken != null) {
          this.getWxOpenConfigStorage().updateAuthorizerRefreshToken(authorizerAppid, refreshToken);
        }
      }
    }
    return ret;
  }

  @Override
  public WxOpenAuthorizerOptionResult getAuthorizerOption(String authorizerAppid, String optionName) throws WxErrorException {
    JsonObject jsonObject = new JsonObject();
    jsonObject.addProperty("component_appid", getWxOpenConfigStorage().getComponentAppId());
    jsonObject.addProperty("authorizer_appid", authorizerAppid);
    jsonObject.addProperty("option_name", optionName);
    String responseContent = post(API_GET_AUTHORIZER_OPTION_URL, jsonObject.toString());
    return WxOpenGsonBuilder.create().fromJson(responseContent, WxOpenAuthorizerOptionResult.class);
  }

  @Override
  public void setAuthorizerOption(String authorizerAppid, String optionName, String optionValue) throws WxErrorException {
    JsonObject jsonObject = new JsonObject();
    jsonObject.addProperty("component_appid", getWxOpenConfigStorage().getComponentAppId());
    jsonObject.addProperty("authorizer_appid", authorizerAppid);
    jsonObject.addProperty("option_name", optionName);
    jsonObject.addProperty("option_value", optionValue);
    post(API_SET_AUTHORIZER_OPTION_URL, jsonObject.toString());
  }

  @Override
  public String getAuthorizerAccessToken(String appId, boolean forceRefresh) throws WxErrorException {
    WxOpenConfigStorage config = getWxOpenConfigStorage();
    if (!config.isAuthorizerAccessTokenExpired(appId) && !forceRefresh) {
      return config.getAuthorizerAccessToken(appId);
    }
    Lock lock = config.getWxMpConfigStorage(appId).getAccessTokenLock();
    boolean locked = false;
    try {
      do {
        locked = lock.tryLock(100, TimeUnit.MILLISECONDS);
        if (!forceRefresh && !config.isAuthorizerAccessTokenExpired(appId)) {
          return config.getAuthorizerAccessToken(appId);
        }
      } while (!locked);

      JsonObject jsonObject = new JsonObject();
      jsonObject.addProperty("component_appid", getWxOpenConfigStorage().getComponentAppId());
      jsonObject.addProperty("authorizer_appid", appId);
      jsonObject.addProperty("authorizer_refresh_token", getWxOpenConfigStorage().getAuthorizerRefreshToken(appId));
      String responseContent = post(API_AUTHORIZER_TOKEN_URL, jsonObject.toString());

      WxOpenAuthorizerAccessToken wxOpenAuthorizerAccessToken = WxOpenAuthorizerAccessToken.fromJson(responseContent);
      config.updateAuthorizerAccessToken(appId, wxOpenAuthorizerAccessToken);
      config.updateAuthorizerRefreshToken(appId, wxOpenAuthorizerAccessToken.getAuthorizerRefreshToken());
      return config.getAuthorizerAccessToken(appId);
    } catch (InterruptedException e) {
      throw new WxRuntimeException(e);
    } finally {
      if (locked) {
        lock.unlock();
      }
    }
  }

  @Override
  public WxOAuth2AccessToken oauth2getAccessToken(String appId, String code) throws WxErrorException {
    String url = String.format(OAUTH2_ACCESS_TOKEN_URL, appId, code, getWxOpenConfigStorage().getComponentAppId());
    String responseContent = get(url);
    return WxOAuth2AccessToken.fromJson(responseContent);
  }

  @Override
  public boolean checkSignature(String appid, String timestamp, String nonce, String signature) {
    return false;
  }

  @Override
  public WxOAuth2AccessToken oauth2refreshAccessToken(String appId, String refreshToken) throws WxErrorException {
    String url = String.format(OAUTH2_REFRESH_TOKEN_URL, appId, refreshToken, getWxOpenConfigStorage().getComponentAppId());
    String responseContent = get(url);
    return WxOAuth2AccessToken.fromJson(responseContent);
  }

  @Override
  public String oauth2buildAuthorizationUrl(String appId, String redirectURI, String scope, String state) {
    return String.format(CONNECT_OAUTH2_AUTHORIZE_URL,
      appId, URIUtil.encodeURIComponent(redirectURI), scope, StringUtils.trimToEmpty(state), getWxOpenConfigStorage().getComponentAppId());
  }

  @Override
  public WxMaJscode2SessionResult miniappJscode2Session(String appId, String jsCode) throws WxErrorException {
    String url = String.format(MINIAPP_JSCODE_2_SESSION, appId, jsCode, getWxOpenConfigStorage().getComponentAppId());
    String responseContent = get(url);
    return WxMaJscode2SessionResult.fromJson(responseContent);
  }

  @Override
  public List<WxOpenMaCodeTemplate> getTemplateDraftList() throws WxErrorException {
    String responseContent = get(GET_TEMPLATE_DRAFT_LIST_URL, "access_token");
    JsonObject response = GsonParser.parse(StringUtils.defaultString(responseContent, "{}"));
    boolean hasDraftList = response.has("draft_list");
    if (hasDraftList) {
      return WxOpenGsonBuilder.create().fromJson(response.getAsJsonArray("draft_list"),
        new TypeToken<List<WxOpenMaCodeTemplate>>() {
        }.getType());
    } else {
      return null;
    }
  }

  @Override
  public List<WxOpenMaCodeTemplate> getTemplateList() throws WxErrorException {
    String responseContent = get(GET_TEMPLATE_LIST_URL, "access_token");
    JsonObject response = GsonParser.parse(StringUtils.defaultString(responseContent, "{}"));
    boolean hasTemplateList = response.has("template_list");
    if (hasTemplateList) {
      return WxOpenGsonBuilder.create().fromJson(response.getAsJsonArray("template_list"),
        new TypeToken<List<WxOpenMaCodeTemplate>>() {
        }.getType());
    } else {
      return null;
    }
  }

  @Override
  public void addToTemplate(long draftId) throws WxErrorException {
    JsonObject param = new JsonObject();
    param.addProperty("draft_id", draftId);
    post(ADD_TO_TEMPLATE_URL, param.toString(), "access_token");
  }

  @Override
  public void deleteTemplate(long templateId) throws WxErrorException {
    JsonObject param = new JsonObject();
    param.addProperty("template_id", templateId);
    post(DELETE_TEMPLATE_URL, param.toString(), "access_token");
  }

  /**
   * ????????????????????????????????????????????????
   *
   * @param appId      ??????appId ?????????/?????????
   * @param appIdType  ????????????   ?????????/?????????
   * @param requestUrl ????????????
   * @param param      ????????????
   * @return ????????????
   * @throws WxErrorException
   */
  private String openAccountServicePost(String appId, String appIdType, String requestUrl, JsonObject param) throws WxErrorException {
    String result = "";
    switch (appIdType) {
      case WxConsts.AppIdType.MP_TYPE:
        WxMpService wxMpService = this.getWxMpServiceByAppid(appId);
        result = wxMpService.post(requestUrl, param.toString());
        return result;
      case WxConsts.AppIdType.MINI_TYPE:
        WxOpenMaService maService = this.getWxMaServiceByAppid(appId);
        result = maService.post(requestUrl, param.toString());
        return result;
      default:
        throw new WxErrorException("appIdType????????????");
    }
  }

  @Override
  public WxOpenCreateResult createOpenAccount(String appId, String appIdType) throws WxErrorException {
    JsonObject param = new JsonObject();
    param.addProperty("appid", appId);

    String json = openAccountServicePost(appId, appIdType, CREATE_OPEN_URL, param);

    return WxOpenCreateResult.fromJson(json);
  }


  @Override
  public Boolean bindOpenAccount(String appId, String appIdType, String openAppid) throws WxErrorException {
    JsonObject param = new JsonObject();
    param.addProperty("appid", appId);
    param.addProperty("open_appid", openAppid);

    String json = openAccountServicePost(appId, appIdType, BIND_OPEN_URL, param);
    return WxOpenResult.fromJson(json).isSuccess();
  }


  @Override
  public Boolean unbindOpenAccount(String appId, String appIdType, String openAppid) throws WxErrorException {
    JsonObject param = new JsonObject();
    param.addProperty("appid", appId);
    param.addProperty("open_appid", openAppid);

    String json = openAccountServicePost(appId, appIdType, UNBIND_OPEN_URL, param);
    return WxOpenResult.fromJson(json).isSuccess();
  }


  @Override
  public WxOpenGetResult getOpenAccount(String appId, String appIdType) throws WxErrorException {
    JsonObject param = new JsonObject();
    param.addProperty("appid", appId);

    String json = openAccountServicePost(appId, appIdType, GET_OPEN_URL, param);
    return WxOpenGetResult.fromJson(json);
  }


  @Override
  public WxOpenResult fastRegisterWeapp(String name, String code, String codeType, String legalPersonaWechat, String legalPersonaName, String componentPhone) throws WxErrorException {
    JsonObject jsonObject = new JsonObject();
    jsonObject.addProperty("name", name);
    jsonObject.addProperty("code", code);
    jsonObject.addProperty("code_type", codeType);
    jsonObject.addProperty("legal_persona_wechat", legalPersonaWechat);
    jsonObject.addProperty("legal_persona_name", legalPersonaName);
    jsonObject.addProperty("component_phone", componentPhone);
    String response = post(FAST_REGISTER_WEAPP_URL, jsonObject.toString(), "component_access_token");
    return WxOpenGsonBuilder.create().fromJson(response, WxOpenResult.class);
  }

  @Override
  public WxOpenResult fastRegisterWeappSearch(String name, String legalPersonaWechat, String legalPersonaName) throws WxErrorException {
    JsonObject jsonObject = new JsonObject();
    jsonObject.addProperty("name", name);
    jsonObject.addProperty("legal_persona_wechat", legalPersonaWechat);
    jsonObject.addProperty("legal_persona_name", legalPersonaName);
    String response = post(FAST_REGISTER_WEAPP_SEARCH_URL, jsonObject.toString(), "component_access_token");
    return WxOpenGsonBuilder.create().fromJson(response, WxOpenResult.class);
  }
}
