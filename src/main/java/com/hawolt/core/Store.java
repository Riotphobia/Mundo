package com.hawolt.core;

import com.hawolt.Main;
import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Created: 10/02/2023 05:11
 * Author: Twitter @hawolt
 **/

public class Store implements IStore {
    private final Platform platform;
    private final Account account;
    private final Prepaid prepaid;
    private List<Transaction> transactions;
    private int refundCreditsRemaining;
    private Player player;

    public Store(Account account, IWalletUpdate wallet) throws IOException {
        this.platform = Platform.valueOf(account.get("cpid"));
        this.account = account;
        this.configure(wallet);
        this.prepaid = new Prepaid(account, platform.getEdge(), player);
    }

    public Platform getPlatform() {
        return platform;
    }

    public Player getPlayer() {
        return player;
    }

    public List<Transaction> getTransactions() {
        return transactions;
    }

    public int getRefundCreditsRemaining() {
        return refundCreditsRemaining;
    }

    public List<Transaction> getRecommendedRefunds() {
        return transactions.stream()
                .filter(transaction -> transaction.getRefundabilityMessage() == null)
                .filter(transaction -> transaction.getCurrencyType().equals("RP"))
                .sorted((t1, t2) -> Long.compare(t2.getAmountSpent(), t1.getAmountSpent()))
                .collect(Collectors.toList());
    }

    public boolean redeemPrepaidCode(String code) {
        return prepaid.redeem(player, code);
    }

    @Override
    public int purchaseSummonerNameChange(Currency currency, String name) {
        JSONObject object = new JSONObject();
        JSONObject item = new JSONObject();
        item.put("inventoryType", "SUMMONER_CUSTOMIZATION");
        item.put("itemId", 1);
        item.put("quantity", 1);
        if (currency == Currency.RP) item.put("rpCost", 1300);
        else item.put("ipCost", 13900);
        object.put("accountId", player.getAccountId());
        object.put("summonerName", name);
        JSONArray array = new JSONArray();
        array.put(item);
        object.put("items", array);
        MediaType type = MediaType.parse("application/json");
        RequestBody post = RequestBody.create(type, object.toString());
        Request request = new Request.Builder()
                .url(String.format("https://%s.store.leagueoflegends.com/storefront/v3/summonerNameChange/purchase?language=en_US", platform.translateToWebRegion()))
                .addHeader("Authorization", String.format("Bearer %s", account.get("access_token")))
                .post(post)
                .build();
        Call call = Main.httpClient.newCall(request);
        try (Response response = call.execute()) {
            int status = response.code();
            try (ResponseBody body = response.body()) {
                if (body == null) return status;
                if (status == 200) {
                    if (currency == Currency.RP) {
                        player.withdrawRP(1300);
                    } else {
                        player.withdrawIP(13900);
                    }
                }
                return status;
            }
        } catch (IOException e) {
            return 600;
        }
    }

    private void configure(IWalletUpdate wallet) throws IOException {
        Request request = new Request.Builder()
                .url(String.format("https://%s.store.leagueoflegends.com/storefront/v3/history/purchase?language=en_US", platform.translateToWebRegion()))
                .addHeader("Authorization", String.format("Bearer %s", account.get("access_token")))
                .addHeader("Accept", "application/json")
                .addHeader("Pragma", "no-cache")
                .build();
        Call call = Main.httpClient.newCall(request);
        try (Response response = call.execute()) {
            try (ResponseBody body = response.body()) {
                if (body == null) return;
                JSONObject object = new JSONObject(body.string());
                this.player = new Player(wallet, object.getJSONObject("player"));
                this.refundCreditsRemaining = object.getInt("refundCreditsRemaining");
                this.transactions = object.getJSONArray("transactions")
                        .toList()
                        .stream()
                        .map(o -> (HashMap<?, ?>) o)
                        .map(JSONObject::new)
                        .map(Transaction::new)
                        .collect(Collectors.toList());
            }
        }
    }

    @Override
    public boolean refund(Transaction transaction) {
        JSONObject object = new JSONObject();
        object.put("accountId", player.getAccountId());
        object.put("transactionId", transaction.getTransactionId());
        object.put("inventoryType", transaction.getInventoryType());
        object.put("language", "en_GB");
        MediaType type = MediaType.parse("application/json");
        RequestBody post = RequestBody.create(type, object.toString());
        Request request = new Request.Builder()
                .url(String.format("https://%s.store.leagueoflegends.com/storefront/v3/refund", platform.translateToWebRegion()))
                .addHeader("Authorization", String.format("Bearer %s", account.get("access_token")))
                .post(post)
                .build();
        Call call = Main.httpClient.newCall(request);
        try (Response response = call.execute()) {
            try (ResponseBody body = response.body()) {
                if (body == null) return false;
                boolean status = response.code() == 200;
                if (status) {
                    if (transaction.getCurrencyType().equals("RP")) {
                        player.refundRP(transaction.getAmountSpent());
                    } else {
                        player.refundIP(transaction.getAmountSpent());
                    }
                }
                return status;
            }
        } catch (IOException e) {
            return false;
        }
    }
}
