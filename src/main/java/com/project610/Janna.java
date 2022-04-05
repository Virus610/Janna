package com.project610;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.List;

import com.github.philippheuer.credentialmanager.domain.OAuth2Credential;
import com.github.twitch4j.TwitchClient;
import com.github.twitch4j.TwitchClientBuilder;
import com.github.twitch4j.chat.events.channel.ChannelMessageEvent;
import com.github.twitch4j.eventsub.domain.RedemptionStatus;
import com.github.twitch4j.helix.domain.CustomReward;
import com.github.twitch4j.pubsub.domain.ChannelPointsRedemption;
import com.github.twitch4j.pubsub.events.RewardRedeemedEvent;

import com.netflix.hystrix.exception.HystrixRuntimeException;
import com.project610.structs.JList2;
import net.miginfocom.swing.MigLayout;
import org.apache.commons.lang.StringUtils;
import org.sqlite.SQLiteDataSource;

import javax.swing.*;

import static javax.swing.BoxLayout.LINE_AXIS;
import static javax.swing.BoxLayout.PAGE_AXIS;

public class Janna extends JPanel {

    public static Janna instance;
    private static final int LOG_LEVEL = 6;
    static BufferedReader inputReader = null;
    static BufferedReader channelReader = null;
    static BufferedWriter channelWriter = null;

    public Socket socket = null;
    public Connection sqlCon;
    public static ResultSet EMPTY_RESULT_SET;

    public static ArrayList<String> messages = new ArrayList<>();
    public static ArrayList<Voice> voices = new ArrayList<>();
    public static ArrayList<String> voiceNames = new ArrayList<>();
    public static String defaultVoice = "en-US-Standard-B";
    public static SpeechQueue speechQueue;

    public static HashMap<Integer, User> users = new HashMap<>();
    public static HashMap<String, Integer> userIds = new HashMap<>();

    public static com.github.twitch4j.helix.domain.User mainchannel_user;
    public static String[] extraChannels;
    public static TwitchClient twitch;
    private OAuth2Credential credential;

    public ArrayList<String> muteList = new ArrayList<>();
    public ArrayList<String> whitelist = new ArrayList<>();
    public boolean whitelistOnly = false;


    // Config stuff
    Path configPath = Paths.get("config.ini");
    HashMap<String, String> appConfig;
    String appVersion = "";

    public static HashMap<String,String> filterList = new HashMap<>();
    public static HashMap<String, String> sfxList = new HashMap<>();
    public static HashMap<String, String> responseList = new HashMap<>();

    public Janna(String[] args, JFrame parent) {
        super(new MigLayout("fill, wrap"));

        Janna.instance = this;

        this.parent = parent;

        try {
            initUI();
        } catch (Exception ex) {
            error("Failed to initUI, game over man", ex);
        }
    }

    public void saveAuthToken(String token) {
        try {
            PreparedStatement ps = Janna.instance.sqlCon.prepareStatement("INSERT INTO auth (token) VALUES (?);");
            ps.setString(1, token);
            ps.execute();
        }
        catch (Exception ex) {
            error("Failed to save auth token, that's gon' cause problems", ex);
        }

        setAuthToken(token);
    }

    public void setAuthToken(String token) {
        Creds._helixtoken = token;

        postAuth();
    }


    public static JPanel hbox() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.LINE_AXIS));
        return panel;
    }

    public static JPanel vbox() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.PAGE_AXIS));
        return panel;
    }

    // UI stuff
    JFrame parent;

    JMenuBar menuBar;

    JPanel midPane;
    public static JTextArea chatArea;
    JScrollPane chatScroll;
    JList2 userList;

    JPanel inputPane;
    JTextField inputField;


    public void initUI() throws Exception {
        removeAll();

        setBackground(new Color(50, 50, 100));

        menuBar = new JMenuBar();
        //add(menuBar);
        parent.setJMenuBar(menuBar);
        menuBar.setLayout(new BoxLayout(menuBar, LINE_AXIS));

        JMenu fileMenu = new JMenu("File");
        menuBar.add(fileMenu);

        JMenuItem exitItem = new JMenuItem("Exit");
        exitItem.addActionListener(e -> {
            parent.dispose();
        });
        fileMenu.add(exitItem);

        JMenuItem test = new JMenuItem("test");
        test.addActionListener(e -> {

        });
        fileMenu.add(test);

        JMenu chatMenu = new JMenu("Chat");
        menuBar.add(chatMenu);

        menuBar.add(Box.createHorizontalGlue());

        JMenuItem silenceCurrentItem = new JMenuItem("Kill current message");
        silenceCurrentItem.addActionListener(e-> silenceCurrentVoices());
        chatMenu.add(silenceCurrentItem);

        chatMenu.add(Box.createRigidArea(new Dimension(5,16)));

        JMenuItem silenceAllItem = new JMenuItem("Kill all queued messages");
        silenceAllItem.addActionListener(e-> silenceAllVoices());
        chatMenu.add(silenceAllItem);

        // Mid pane to hold chat area, user list, input box, and send button (h-box)
        midPane = new JPanel(new MigLayout("fill"));
        midPane.setBackground(new Color(50, 50, 100));
        add(midPane, "grow");

        // Chat pane holds all but the user list (v-box)
        JPanel chatPane = new JPanel(new MigLayout("fill"));
        chatPane.setBackground(new Color(50, 50, 100));
        midPane.add(chatPane, "grow");

        chatArea = new JTextArea();
        //chatArea.setPreferredSize(new Dimension(300, 100));
        chatArea.setEditable(false);
        chatArea.setLineWrap(true);
        chatArea.setFont(chatArea.getFont().deriveFont(11f));
        chatScroll = new JScrollPane(chatArea, JScrollPane.VERTICAL_SCROLLBAR_ALWAYS, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        //chatScroll.setPreferredSize(new Dimension(300,400));
        chatPane.add(new JLabel(" "), "north");
        chatPane.add(chatScroll, "grow");

        JPanel userListPane = new JPanel(new MigLayout("filly"));
        userListPane.setBackground(new Color(50, 50, 100));
        midPane.add(userListPane, "east, grow");

        JLabel chattersLabel = new JLabel("<html><font color=white><b>// Chatters</b></font></html>");
        userListPane.add(chattersLabel, "north");

        userList = new JList2<String>();
        userList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        userList.setPrototypeCellValue("___________________");
        userListPane.add (new JScrollPane(userList, JScrollPane.VERTICAL_SCROLLBAR_ALWAYS, JScrollPane.HORIZONTAL_SCROLLBAR_ALWAYS), "growy");

        inputPane = new JPanel(new MigLayout("fillx"));
        inputPane.setBackground(new Color(50, 50, 100));
        add(inputPane, "south, h 30");

        inputField = new JTextField();
        inputField.addKeyListener(new KeyListener() {
            @Override
            public void keyTyped(KeyEvent e) {

            }

            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    sendChat();
                }

            }

            @Override
            public void keyReleased(KeyEvent e) {

            }
        });

        inputPane.add(inputField, "grow"); // This should be in yet another pane??
        JButton sendButton = new JButton("Send");
        sendButton.addActionListener(e -> sendChat());
        inputPane.add(sendButton, "east, w 50");
    }

    // Lazy UI stuff, will eventually obsolete this crap
    public static Component prefSize(Component component, int w, int h) {
        component.setPreferredSize(new Dimension(w, h));
        return component;
    }

    public static Component maxSize(Component component, int w, int h) {
        component.setMaximumSize(new Dimension((w == -1 ? Integer.MAX_VALUE : w), (h == -1 ? Integer.MAX_VALUE : h)));
        return component;
    }

    public static Component rigidSize(Component component, int w, int h) {
        component.setMinimumSize(new Dimension(w, h));
        component.setPreferredSize(new Dimension(w, h));
        component.setMaximumSize(new Dimension(w, h));
        component.setSize(new Dimension(w, h));
        return component;
    }

    public void init() throws Exception {

        appConfig = new HashMap<>();

        // Get app settings
        readAppConfig();


        // SpeechQueue started; Will ready up and play voices as they come
        //  if allowConsecutive is true, different people can 'talk' at the same time
        speechQueue = new SpeechQueue(true);
        new Thread(speechQueue).start();

        // DB stuff; Maybe dumb to try creating tables every time, but eh. Think of this as a lazy 'liquibase' thing
        SQLiteDataSource sqlDataSource = new SQLiteDataSource();
        sqlDataSource.setUrl("jdbc:sqlite:janna.sqlite");

        sqlCon = sqlDataSource.getConnection();
        PreparedStatement emptyStatement = sqlCon.prepareStatement("SELECT 1 WHERE false");
        emptyStatement.execute();
        EMPTY_RESULT_SET = emptyStatement.getResultSet();

        PreparedStatement createUserTable = sqlCon.prepareStatement("CREATE TABLE IF NOT EXISTS user ( "
                + " id INTEGER PRIMARY KEY AUTOINCREMENT "
                + ", username VARCHAR(128) UNIQUE"
                + ", voicename VARCHAR(128) DEFAULT 'en-US-Standard-B'"
                + ", voicespeed DOUBLE DEFAULT 1"
                + ", voicepitch DOUBLE DEFAULT 0"
                + ", voicevolume DOUBLE DEFAULT 1"
                + ", freevoice INTEGER DEFAULT 1"
                + ");"
        );
        createUserTable.execute();

        PreparedStatement createPrefTable = sqlCon.prepareStatement("CREATE TABLE IF NOT EXISTS pref ( "
                + "id INTEGER PRIMARY KEY AUTOINCREMENT"
                + ", name VARCHAR(128) UNIQUE"
                + ");"
        );
        createPrefTable.execute();

        PreparedStatement createUserPrefTable = sqlCon.prepareStatement("CREATE TABLE IF NOT EXISTS user_pref ( "
                + "id INTEGER PRIMARY KEY AUTOINCREMENT"
                + ", user_id INTEGER"
                + ", pref_id INTEGER"
                + ", data VARCHAR(1024)"
                + ");"
        );
        createUserPrefTable.execute();
        addPref("butt_stuff");

        PreparedStatement createAuthTable = sqlCon.prepareStatement("CREATE TABLE IF NOT EXISTS auth ( "
                + "id INTEGER PRIMARY KEY AUTOINCREMENT"
                + ", token VARCHAR(1024)"
                + ");"
        );
        createAuthTable.execute();

        PreparedStatement createReactionTable = sqlCon.prepareStatement("CREATE TABLE IF NOT EXISTS reaction ( "
                + "id INTEGER PRIMARY KEY AUTOINCREMENT"
                + ", type VARCHAR(128)"
                + ", phrase VARCHAR(128) UNIQUE"
                + ", result VARCHAR(1024)"
                + ", extra VARCHAR(1024)"
                + ");"
        );
        createReactionTable.execute();

        // Load reaction stuff
        loadReactions();


        // Get credentials for logging into chat
        credential = new OAuth2Credential("twitch", Creds._password);

        twitch = TwitchClientBuilder.builder()
                .withClientId(Creds._clientid)
                .withEnableChat(true)
                .withChatAccount(credential)
                .withEnablePubSub(true)
                .withEnableKraken(true)
                .withEnableTMI(true)
                .withEnableHelix(true)
                .build();

        twitch.getPubSub().connect();

        // Join channels
        joinChannel(appConfig.get("mainchannel"));
        if (null != appConfig.get("extrachannels")) {
            for (String extraChannel : appConfig.get("extrachannels").split(",")) {
                joinChannel(extraChannel.toLowerCase().trim());
            }
        }

        twitch.getEventManager().onEvent(ChannelMessageEvent.class, this::readMessage);

        // Get Helix auth token (With Kraken api dead, this is really critical)
        Auth.getToken();

        // Add supported voices - TODO: Move to config.ini or something
        voiceNames.add("en-AU-Standard-A");
        voiceNames.add("en-AU-Standard-C");
        voiceNames.add("en-AU-Standard-B");
        voiceNames.add("en-AU-Standard-D");
        voiceNames.add("en-GB-Standard-A");
        voiceNames.add("en-GB-Standard-C");
        voiceNames.add("en-GB-Standard-F");
        voiceNames.add("en-GB-Standard-B");
        voiceNames.add("en-GB-Standard-D");
        voiceNames.add("en-IN-Standard-A");
        voiceNames.add("en-IN-Standard-D");
        voiceNames.add("en-IN-Standard-B");
        voiceNames.add("en-IN-Standard-C");
        voiceNames.add("en-US-Standard-C");
        voiceNames.add("en-US-Standard-E");
        voiceNames.add("en-US-Standard-G");
        voiceNames.add("en-US-Standard-H");
        voiceNames.add("en-US-Standard-B");
        voiceNames.add("en-US-Standard-D");
        voiceNames.add("en-US-Standard-I");
        voiceNames.add("en-US-Standard-J");

        // TODO: This obviously shouldn't be hardcoded. Work into a /mute command and store in DB
        muteList.add("ircbot610");
        muteList.add("buttsbot");
        muteList.add("saltlogic");
        muteList.add("streamlabs");
        muteList.add("streamelements");

        // Speech/text to verify that Janna is alive and well (Hopefully!)
        // TODO: Make 'initialized' phrase configurable
        new Voice("Is Google text to speech working and stuff?", null);
        info("Beware I live");
    }

    private ResultSet getReaction(String type) {
        try {
            PreparedStatement getReactions = sqlCon.prepareStatement("SELECT * FROM reaction WHERE type=?;");
            getReactions.setString(1, type);
            getReactions.execute();
            return getReactions.getResultSet();
        } catch (SQLException ex) {
            error("SQL Exception while getting reactions for type: " + type,ex);
        }
        return EMPTY_RESULT_SET;
    }

    private ResultSet getReactions() {
        try {
            PreparedStatement getReactions = sqlCon.prepareStatement("SELECT * FROM reaction;");
            return getReactions.executeQuery();
        } catch (SQLException ex) {
            error("SQL Exception while getting reactions", ex);
        }
        return EMPTY_RESULT_SET;
    }

    private void loadReactions() {
        try {
            ResultSet result = getReactions();
            if (result.isClosed()) return;

            do {
                String type = result.getString("type");
                String key = result.getString("phrase");
                String value = result.getString("result");
                String extra = result.getString("extra");

                if (type.equalsIgnoreCase("filter")) {
                    filterList.put(key, value);
                } else if (type.equalsIgnoreCase("sfx")) {
                    sfxList.put(key, value);
                } else if (type.equalsIgnoreCase("response")) {
                    responseList.put(key,value);
                }
            } while (result.next());
        } catch (Exception ex) {
            error("Failed to load reactions from DB", ex);
        }
    }

    private void readAppConfig() throws Exception {
        try {
            List<String> config = Files.readAllLines(configPath);
            for (String s : config) {
                s = s.trim();
                if (s.isEmpty() || s.charAt(0) == '#') continue;

                String key = s.split("=", 2)[0];
                String value = s.split("=", 2)[1];

                appConfig.put(key, value);
            }

            Creds._username = appConfig.get("username");
            Creds._password = appConfig.get("oauth");

        } catch (Exception ex) {
            warn("Failed to load username or oauth token, please update `config.ini` with your chat credentials");
            if (!Files.exists(configPath)) {
                Files.write(configPath, (
                        "# Login credentials go here. You must not use your password, but an OAUTH token,\n" +
                                "# which you can get from here: https://twitchapps.com/tmi/\n" +
                                "username=\n" +
                                "oauth=\n" +
                                "\n" +
                                "# Primary channel to listen for chat (And handle channel point redemptions)\n" +
                                "mainchannel=\n" +
                                "\n" +
                                "# This is kinda wonky right now. It'll read out stuff from other channels, and any messages sent by the bot will be sent to these channels as well\n" +
                                "# (Comma separated list, eg: channel6,channel1,channel0)\n" +
                                "extrachannels=\n").getBytes());
            }
        }


        try {
            InputStream is = getClass().getClassLoader().getResourceAsStream(".properties");

            //properties = Files.readAllLines(Paths.get(getClass().getClassLoader().getResource(".properties").toURI()));
            BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("version=")) {
                    appVersion = line.substring(line.indexOf("=") + 1);
                    if (null == appConfig.get("version") || !appConfig.get("version").equalsIgnoreCase(appVersion)) {
                        appConfig.put("version", appVersion);
                        // TODO: Deal with new app versions somehow
//                        writeSettings();
//                        newVersion = true;
                    }
                    parent.setTitle(parent.getTitle().replace("%VERSION%",  "v" + appVersion));
                }
            }
        } catch (Exception ex) {
            // If we can't read from resources, we got problems
        }
    }

    // Yeah
    protected void joinChannel(String channel) {
        try {
            twitch.getChat().joinChannel(channel);
        } catch (Exception ex) {
            error("Couldn't connect to channel: " + channel, ex);
        }
    }

    // Runs after authentication is completed; Can't do Helix API stuff until that's been done
    public void postAuth() {
        try {
            mainchannel_user = twitch.getHelix().getUsers(Creds._helixtoken, null, Arrays.asList(appConfig.get("mainchannel"))).execute().getUsers().get(0);

            twitch.getPubSub().listenForChannelPointsRedemptionEvents(credential, mainchannel_user.getId());
            twitch.getEventManager().onEvent(RewardRedeemedEvent.class, this::rewardRedeemed);

            setupCustomRewards();
        }
        catch (Exception ex) {
            error("Error doing postAuth stuff", ex);
        }
    }

    // Twitch won't let a bot mark rewards as Redeemed, Canceled, etc. unless the bot made the rewards.
    // TODO: Store titles (Which are used for redemption-handling code) in a HashMap for consistency's sake
    // TODO-maybe: Make this configurable outside the code
    private void setupCustomRewards() {

        generateTTSReward(
                "TTS: Slow down my voice",
                "Make your TTS voice slightly slower",
                256,
                "#3664A1",
                false,
                true
        );

        generateTTSReward(
                "TTS: Speed up my voice",
                "Make your TTS voice slightly faster",
                256,
                "#FF6905",
                false,
                true
        );

        generateTTSReward(
                "TTS: Lower my voice pitch",
                "Make your TTS voice a little deeper",
                256,
                "#FA2929",
                false,
                true
        );

        generateTTSReward(
                "TTS: Raise my voice pitch",
                "Make your TTS voice a little higher",
                256,
                "#58FF0D",
                false,
                true
        );

        generateTTSReward(
                "TTS: Set my voice accent",
                "Pick a base TTS voice from this list (eg: en-GB-Standard-A): https://docs.google.com/spreadsheets/d/1hrhoy3yoLjKE_N_XgHwG8qFAWWxMch6CerqV2xX-XOs",
                4096,
                "#29FAEA",
                true,
                true
        );

        generateTTSReward(
                "*NEWCOMERS ONLY* TTS: Set my voice accent",
                "You can only do this once! Pick a base TTS voice from this list (eg: en-GB-Standard-A): https://docs.google.com/spreadsheets/d/1hrhoy3yoLjKE_N_XgHwG8qFAWWxMch6CerqV2xX-XOs",
                300,
                "#94FA3A",
                true,
                true
        );
    }

    // Upload the reward to Twitch
    // TODO: More elegant exception handling, and probably check if it exists before uploading (Sorta the same thing)
    private void uploadCustomReward(CustomReward reward) {
        try {
            twitch.getHelix().createCustomReward(Creds._helixtoken, mainchannel_user.getId(), reward).execute();
        } catch (HystrixRuntimeException ex) {
            debug("Most likely a duplicate, because I was too lazy to check for pre-existing: " + reward.getTitle());
        }
    }

    // Generate a TTS-specific reward. If no modification is desired (eg: From the generateSimplifiedCustomReward root)
    //  then set uploadImmediately=true to just fire and forget
    private CustomReward generateTTSReward (String title, String prompt, int cost, String backgroundColor, boolean inputRequired, boolean uploadImmediately) {
        CustomReward reward = generateSimplifiedCustomReward()
                .title(title)
                .prompt(prompt)
                .cost(cost)
                .backgroundColor(backgroundColor)
                .isUserInputRequired(inputRequired)
                .build();

        if (uploadImmediately) {
            uploadCustomReward(reward);
        }

        return reward;
    }

    // The CustomRewardBuilder has a lot of params that I don't much care about, so this auto-sets some stuff
    private CustomReward.CustomRewardBuilder generateSimplifiedCustomReward() {
        return CustomReward.builder()

                .globalCooldownSetting(CustomReward.GlobalCooldownSetting.builder().globalCooldownSeconds(0).isEnabled(false).build())
                .maxPerStreamSetting(CustomReward.MaxPerStreamSetting.builder().isEnabled(false).maxPerStream(0).build())
                .maxPerUserPerStreamSetting(CustomReward.MaxPerUserPerStreamSetting.builder().isEnabled(false).maxPerUserPerStream(0).build())
                .shouldRedemptionsSkipRequestQueue(false)

                .isEnabled(true)
                .isPaused(false)
                .isInStock(true)

                .broadcasterId(mainchannel_user.getId())
                .broadcasterLogin(mainchannel_user.getLogin())
                .broadcasterName(mainchannel_user.getDisplayName())
                .id(UUID.randomUUID().toString());
    }

    // Handle messages/commands typed into the bot UI - Currently not read out loud, but this could be configurable
    private void sendChat() {
        String message = inputField.getText();
        inputField.setText("");

        if (message.trim().isEmpty()) {
            return;
        }

        if (message.charAt(0) == '/') {
            String[] split = message.split(" ");
            String cmd = split[0].substring(1).trim();

            if (cmd.equalsIgnoreCase("j") || cmd.equalsIgnoreCase("join")) {
                String channel = split[1].replace("#","");
                twitch.getChat().joinChannel(channel);
            }

        } else {
            sendMessage(appConfig.get("mainchannel"), message);
            chat(Creds._username + ": " + message);
        }
    }

    // Handle redemptions
    void rewardRedeemed(RewardRedeemedEvent event) {
        ChannelPointsRedemption redemption = event.getRedemption();
        String username = redemption.getUser().getDisplayName();
        String reward = redemption.getReward().getTitle();
        String channel = twitch.getChat().getChannelIdToChannelName().get(event.getRedemption().getChannelId());

        User currentUser = getUser(username);
        int redeemed = 0;

        if (reward.equalsIgnoreCase("Change the default TTS voice (random)")) {
            String actuallyDefaultVoice = defaultVoice;
            while (defaultVoice == actuallyDefaultVoice) {
                int rand = new Random().nextInt(voiceNames.size());
                defaultVoice = voiceNames.get(rand);
                System.out.println("New current voice: " + rand + " -> " + defaultVoice);
                redeemed = 1;
            }
        }
        else if (reward.equalsIgnoreCase("TTS: Lower my voice pitch")) {
            if (currentUser.voicePitch > -20) {
                currentUser.voicePitch -= 1;
                currentUser.save();
                redeemed = 1;
            } else {
                sendMessage(channel,username + ": Your voice is as low as it gets! (@" + username + ")");
            }
        }
        else if (reward.equalsIgnoreCase("TTS: Raise my voice pitch")) {
            if ( currentUser.voicePitch < 20) {
                currentUser.voicePitch += 1;
                currentUser.save();
                redeemed = 1;
            }
            else {
                sendMessage(channel,username + ": I can't raise your voice any higher than this (@" + username + ")");
            }
        }
        else if (reward.equalsIgnoreCase("TTS: Slow down my voice")) {
            if (currentUser.voiceSpeed > 0.75) {
                currentUser.voiceSpeed -= 0.25;
                currentUser.save();
                redeemed = 1;
            } else {
                sendMessage(channel,"Your voice is already minimum speed (@" + username + "");
            }
        }
        else if (reward.equalsIgnoreCase("TTS: Speed up my voice")) {
            if ( currentUser.voiceSpeed < 4.0) {
                currentUser.voiceSpeed += 0.25;
                currentUser.save();
                redeemed = 1;
            }
            else {
                sendMessage(channel,"Your voice is already max speed (@" + username + "");
            }
        }
        else if (reward.equalsIgnoreCase("TTS: Set my voice accent")) {
            redeemed = changeUserVoice(currentUser, event.getRedemption().getUserInput().trim(), channel,false);
        }
        // The way this is intended to work is: New people get 1 'free' accent change, subsequent changes are
        //  considerably more expensive, to keep people from confusing the streamer with constant significant changes
        else if (reward.equalsIgnoreCase("*NEWCOMERS ONLY* TTS: Set my voice accent")) {
            if (currentUser.freeVoice > 0) {
                redeemed = changeUserVoice(currentUser, event.getRedemption().getUserInput().trim(), channel,true);
            }
            else {
                sendMessage(channel,"Scam detected. I'm keeping those channel points.  (@" + currentUser.name + ")");
                redeemed = 1;
            }
        }
        // If not handled by this bot, don't deal with redemption
        else {
            redeemed = -1;
        }

        // Auto-mark redeemed/canceled for stuff that's been handled
        if (redeemed != -1) {
            Collection<String> redemption_ids = new ArrayList<>();
            redemption_ids.add(redemption.getId());
            if (redeemed == 1) {
                redemption.setStatus("FULFILLED");
                twitch.getHelix().updateRedemptionStatus(Creds._helixtoken, mainchannel_user.getId(),
                        redemption.getReward().getId(), redemption_ids, RedemptionStatus.FULFILLED).execute();
            } else {
                redemption.setStatus("CANCELED");
                twitch.getHelix().updateRedemptionStatus(Creds._helixtoken, mainchannel_user.getId(),
                        redemption.getReward().getId(), redemption_ids, RedemptionStatus.CANCELED).execute();
            }
        }
    }

    // Change accent - Could probably tie the speed/pitch into this later as well. TODO
    public int changeUserVoice(User currentUser, String input, String channel, boolean freebie) {
        if (voiceNames.contains(input)) {
            if (!currentUser.voiceName.equalsIgnoreCase(input)) {
                currentUser.voiceName = input;
                if (freebie && currentUser.freeVoice > 0) currentUser.freeVoice--;
                currentUser.save();
                return 1;
            } else {
                sendMessage(channel,"You're already using that voice (@" + currentUser.name + ")");
            }
        } else {
            sendMessage(channel,"That voice isn't supported (@" + currentUser.name + ")");
        }
        return 0;
    }

    // Write updated user settings to DB
    public void saveUser(User user) {
        try {
            PreparedStatement update = sqlCon.prepareStatement("UPDATE user SET"
                    + " voicename=?"
                    + ", voicespeed=?"
                    + ", voicepitch=?"
                    + ", voicevolume=?"
                    + ", freevoice=?"
                    + " WHERE id=?"
            );
            update.setString(1, user.voiceName);
            update.setDouble(2, user.voiceSpeed);
            update.setDouble(3, user.voicePitch);
            update.setDouble(4,user.voiceVolume);
            update.setDouble(5,user.freeVoice);
            update.setInt(6,user.id);
            update.executeUpdate();
        } catch (Exception ex) {
            error("Failed to save user: " + user.name, ex);
        }
    }

    // Send a message to Twitch
    public static void sendMessage(String s) {
        for (String channel : twitch.getChat().getChannels()) {
            sendMessage(channel, s);
        }
    }

    // Send a message to a specific channel on Twitch
    public static void sendMessage(String channel, String s) {
        twitch.getChat().sendMessage(channel, s);
    }

    // Screw around with the text of a message before having it read aloud (Anti-spam measures will go here)
    public static String butcher(String s, User user) {
        String result = "";
        s = s.toLowerCase();


        for (String find : filterList.keySet()) {
            String replace = filterList.get(find);
            s = s.replaceAll(find, replace);
        }

        int tempWordCount = 0;
        String tempWord = "";
        String[] words = s.split(" ");

        // Anti-spam and anti-annoyance measures
        for (String word : words) {
            // Limit repeated words to 3 in a row
            if (tempWord.equals(word)) {
                if (++tempWordCount > 2) {
                    continue;
                }
            } else {
                tempWord = word;
                tempWordCount = 0;
            }

            // Limit repeated characters to 3 in a row
            if (word.matches("(.)(\\1){2,}")) {
                StringBuilder sb = new StringBuilder(word);
                char tempChar = ' ';
                int tempCharCount = 0;
                for (int i = 0; i < sb.length(); i++) {
                    if (tempChar == sb.charAt(i))
                    {
                        tempCharCount++;
                        if (tempCharCount > 2)
                        {
                            sb.deleteCharAt(i);
                            i--;
                        }
                    }
                    else {
                        tempChar = sb.charAt(i);
                        tempCharCount = 0;
                    }
                    word=sb.toString();
                }
            }
            result += word + " ";
        }

        // This is super immature. 3% of messages will end with "But enough about my butt", because it's funny.
        // Chatters can opt out of this with the command !dontbuttmebro
        // TODO: Make configurable for people who aren't as childish as me
        if (Math.random() > 0.97 && !"0".equals(user.prefs.get("butt_stuff"))) {
            result += " But enough about my butt.";
        }
        return result;
    }

    private ResultSet db_getUser(String username) {
        try {
            PreparedStatement select = sqlCon.prepareStatement("SELECT * FROM user WHERE username LIKE ? LIMIT 1");
            select.setString(1, username);
            select.execute();

            return select.getResultSet();

        } catch (Exception ex) {
            return EMPTY_RESULT_SET;
        }
    }

    private User generateNewUser(ResultSet result) {
        try {
            return new User(
                    this
                    , result.getInt("id")
                    , result.getString("username")
                    , result.getString("voicename")
                    , result.getDouble("voicespeed")
                    , result.getDouble("voicepitch")
                    , result.getDouble("voicevolume")
                    , result.getInt("freevoice")
            );
        } catch (Exception ex) {
            return null;
        }
    }

    public User getUser(String username) {
        username = username.toLowerCase();
        User currentUser = users.get(userIds.get(username));

        // User not in memory yet
        if (null == currentUser) {
            System.out.println("Adding user to memory: " + username);
            try {
                if (null == sqlCon) {
                    return null;
                }

                ResultSet result = db_getUser(username);
                // If user DNE in DB
                if (!result.next()) {
                    System.out.println("Adding user to DB: " + username);
                    PreparedStatement insert = sqlCon.prepareStatement("INSERT INTO user (username) VALUES (?)");
                    insert.setString(1, username);
                    insert.executeUpdate();
                    insert.close();

                    ResultSet result2 = db_getUser(username);
                    if (result2.next()) {
                        currentUser = generateNewUser(result2);
                    } else {
                        // This probably isn't an issue anymore
                        warn("How can result2 be empty? I literally just inserted the thing I was looking for: " + username);
                    }
                } else {
                    currentUser = generateNewUser(result);
                }
                users.put(currentUser.id, currentUser);
                userIds.put(currentUser.name, currentUser.id);

                if (!userList.getItems().contains(username)) {
                    userList.add(username);
                }
            } catch (Exception ex) {
                error("SQL broke", ex);
            }
        }
        return currentUser;
    }

    private void addPref(String name) {
        try {
            PreparedStatement prefQuery = sqlCon.prepareStatement("INSERT INTO pref (name) SELECT '" + name + "' "
                    + " WHERE NOT EXISTS (SELECT 1 FROM pref WHERE name = '" + name + "');");
            prefQuery.execute();
        } catch (Exception ex) {
            error("Failed to add pref `" + name + "` to DB", ex);
        }

    }

    public HashMap<String, String> getUserPrefs(User user) {
        try {
            HashMap<String, String> prefs = new HashMap<>();
            PreparedStatement userPrefsQuery = sqlCon.prepareStatement("SELECT * FROM user_pref up JOIN pref p ON p.id = up.pref_id WHERE up.user_id = " + user.id +";");
            ResultSet result = userPrefsQuery.executeQuery();
            System.out.println("User prefs for "+user.name+":");
            while (result.next()) {
                System.out.println(result.getString("name") + "=" + result.getString("data"));
                prefs.put(result.getString("name"), result.getString("data"));
            }
            return prefs;
        } catch (Exception ex) {
            error("Messed up getting user prefs", ex);
        }
        return null;
    }

    public boolean setUserPref(User user, String name, String data) {
        try {
            if (data.equals(user.prefs.get(name))) { //
                System.out.println("Pref already set: " + user.name + "." + name + "=" + data);
                return false;
            } else if (null != user.prefs.get(name)) {
                PreparedStatement query = sqlCon.prepareStatement("UPDATE user_pref SET data='"+data+"' "
                        + " WHERE user_id="+user.id+" AND pref_id = (SELECT id FROM pref WHERE name='" + name + "'"
                        + ");");
                query.execute();
            } else {
                PreparedStatement query = sqlCon.prepareStatement("INSERT INTO user_pref (user_id, pref_id, data) VALUES ( "
                        + user.id
                        + ", (SELECT id FROM pref WHERE name='" + name + "')"
                        + ", '" + data + "'"
                        + ");");
                query.execute();
            }
            user.prefs.put(name, data);

        } catch (Exception ex) {
            error("Error setting user pref `"+name+"="+data+"` for `"+user.name+"`", ex);
            return false;
        }
        return true;
    }

    public ResultSet executeQuery(String query) throws SQLException {
        PreparedStatement prep = sqlCon.prepareStatement(query);
        return prep.executeQuery();
    }

    public int executeUpdate(String query) throws SQLException {
        PreparedStatement prep = sqlCon.prepareStatement(query);
        return prep.executeUpdate();
    }

    // Logging stuff, should probably move this into its own class
    public static void console(String s, int level) {
        if (level <= Janna.LOG_LEVEL) {
            chatArea.append("\n" + s);
            chatArea.setCaretPosition(chatArea.getDocument().getLength());
        }
    }

    public static void debug (String s) {
        console("[DEBUG] " + s, 7);
        //System.out.println(s);
    }

    public static void info (String s) {
        console("[INFO]  " + s, 6);
    }

    public static void chat (String s) {
        console("" + s, 5);
    }

    public static void warn (String s) {
        console("[WARN]  " + s, 4);
    }

    public static void error(String s, Exception ex) {
        System.err.println(s);
        console("[ERROR] " + s, 3);

        if (null != ex) {
            ex.printStackTrace();

            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            ex.printStackTrace(pw);
            console(sw.toString() + "\n", 3);
        }
    }

    // Incoming messages handled here
    private void readMessage(ChannelMessageEvent e) {

        String name = e.getUser().getName();
        String channel = e.getChannel().getName();
        User user = getUser(name);

        for (String key : e.getMessageEvent().getBadges().keySet()) {
            String value = e.getMessageEvent().getBadges().get(key);
            user.roles.put(key, value);
        }
        String message = e.getMessage();
        chat(name + ": " + message);

        HashMap<String, Integer> emotes = getEmotes(e);

        for (String phrase : responseList.keySet()) {
            if (message.toLowerCase().contains(phrase.toLowerCase())) {
                sendMessage(channel, responseList.get(phrase));
            }
        }

        if (message.charAt(0) == '!') {
            parseCommand(message, user, channel);
            return;
        }

        boolean canSpeak = true;

        if (muteList.contains(user.name.toLowerCase())) {
            debug("Not speaking, user is muted");
            return;
        }

        if (whitelistOnly) {
            if (false/*isOp*/) {

            }
            else if (!whitelist.contains(user.name.toLowerCase())) {
                return;
            }
        }

        if (user.voiceVolume <= 0) {
            return;
        }

        if (canSpeak) {
            if (false) { // TODO: Read names
                message = user.name + ": " + message;
            }
            new Voice(ssmlify(butcher(message, user), emotes), user);
        }
        //new Speaker(message).start();
    }

    // Mess with the text to do stuff like read emotes faster, or play sound effects
    private String ssmlify(String message, HashMap<String, Integer> emotes) {
        // Sanitize so peeps don't do bad custom SSML
        message = message.replace("<", "less than").replace(">", "greater than");
        int emoteCount = 0;
        for (String emote : emotes.keySet()) {
            emoteCount += emotes.get(emote);
        }
        for (String emote : emotes.keySet()) {
            // The more times an emote shows up in a message, the faster it'll be read, to discourage spam, maybe.
            message = message.replace(emote, "<prosody rate=\""+(150+25*emoteCount)+"%\" volume=\""+(-2-1*emoteCount)+"dB\">" + emote + "</prosody>");
        }

        // Handle sfx - Only play the first to avoid unholy noise spam
        int soundPos = Integer.MAX_VALUE;
        String find = "";
        String replace = "";
        for (String key : sfxList.keySet()) {
            if (message.contains(key) && message.indexOf(key) < soundPos) {
                soundPos = message.indexOf(key);
                find=key;
                replace = "<audio src=\""+sfxList.get(find)+"\" >"+find+"</audio>";
            }
        }

        message = message.replaceFirst(find, replace);

        return "<speak>" + message + "</speak>";
    }

    // Gracefully borrowed from the Twitch4J discord server
    private HashMap<String, Integer> getEmotes(ChannelMessageEvent e) {
        HashMap<String, Integer> emoteList = new HashMap<>();
            final String msg = e.getMessage();
            final int msgLength = msg.length();
            e.getMessageEvent().getTagValue("emotes")
                    .map(emotes -> StringUtils.split(emotes, '/'))
                    .ifPresent(emotes -> {
                        for (String emoteStr : emotes) {
                            final int indexDelim = emoteStr.indexOf(':');
                            final String emoteId = emoteStr.substring(0, indexDelim);
                            final String indices = emoteStr.substring(indexDelim + 1);
                            final String[] indicesArr = StringUtils.split(indices, ',');
                            for (String specificIndex : indicesArr) {
                                final int specificDelim = specificIndex.indexOf('-');
                                final int startIndex = Math.max(Integer.parseInt(specificIndex.substring(0, specificDelim)), 0);
                                final int endIndex = Math.min(Integer.parseInt(specificIndex.substring(specificDelim + 1)) + 1, msgLength);
                                final String emoteName = msg.substring(startIndex, endIndex);
                                if (null == emoteList.get(emoteName.toLowerCase())){
                                    emoteList.put(emoteName.toLowerCase(), 1);
                                } else {
                                    emoteList.put(emoteName.toLowerCase(), emoteList.get(emoteName.toLowerCase())+1);
                                }
                            }
                        }
                    });
            return emoteList;
    }

    // Incoming messages starting with `!` handled here
    private void parseCommand(String message, User user, String channel) {
        message = message.substring(1);
        String[] split = message.split(" ");
        String cmd = split[0];


        if (cmd.equalsIgnoreCase("no")) {
            if (!isMod(user.name)) return;
            silenceCurrentVoices();
        } else if (cmd.equalsIgnoreCase("stfu")) {
            if (!isMod(user.name)) return;
            silenceAllVoices();
        } else if (cmd.equalsIgnoreCase("janna.addsfx")) {
            if (!isMod(user.name)) return;
            addReaction("sfx", message, channel);
        } else if (cmd.equalsIgnoreCase("janna.addfilter")) {
            if (!isMod(user.name)) return;
            addReaction("filter", message, channel);
        } else if (cmd.equalsIgnoreCase("janna.addresponse")) {
            if (!isMod(user.name)) return;
            addReaction("response", message, channel);
        } else if (cmd.equalsIgnoreCase("janna.removesfx")) {
            if (!isMod(user.name)) return;
            removeReaction("sfx", message, channel);
        } else if (cmd.equalsIgnoreCase("janna.removefilter")) {
            if (!isMod(user.name)) return;
            removeReaction("filter", message, channel);
        } else if (cmd.equalsIgnoreCase("janna.removeresponse")) {
            if (!isMod(user.name)) return;
            removeReaction("response", message, channel);
        }
        else if (cmd.equalsIgnoreCase("dontbuttmebro")) {
            if (setUserPref(user, "butt_stuff", "0")) {
                twitch.getChat().sendMessage(appConfig.get("mainchannel"), "Okay, I won't butt you, bro.");
            }
        } else if (cmd.equalsIgnoreCase("dobuttmebro")) {
            if (setUserPref(user, "butt_stuff", "1")) {
                twitch.getChat().sendMessage(appConfig.get("mainchannel"), "Can't get enough of that butt.");
            }
        } else if (cmd.equalsIgnoreCase("mute")) {
            // TODO
        }
    }

    public void silenceCurrentVoices() {
        for (int i = speechQueue.currentlyPlaying.size()-1; i >= 0; i--) {
            try {
                if (speechQueue.currentlyPlaying.get(i).started) {
                    speechQueue.currentlyPlaying.get(i).clip.stop();
                    speechQueue.currentlyPlaying.get(i).busy = false;
                }
            } catch (Exception ex) {

            }
        }
    }

    public void silenceAllVoices() {
        try {
            for (int i = speechQueue.currentlyPlaying.size() - 1; i >= 0; i--) {
                speechQueue.currentlyPlaying.get(i).clip.stop();
                speechQueue.currentlyPlaying.get(i).busy = false;
            }
        } catch (Exception ex) {

        }
    }

    public void addReaction (String type, String message, String channel) {
        String[] split = message.split(" ",3);
        String phrase = "", result = "";
        try {
            phrase = split[1].toLowerCase();
            result = split[2];
            String query = "INSERT INTO reaction (type, phrase, result) VALUES ('"+type+"', '"+phrase+"', '"+result+"');";
            executeUpdate(query);
            switch (type) {
                case "sfx":
                    sfxList.put(phrase, result);
                    sendMessage(channel,"Added SFX for phrase: " + phrase);
                    break;
                case "filter":
                    filterList.put(phrase, result);
                    sendMessage(channel,"Added filter for phrase: " + phrase);
                    break;
                case "response":
                    responseList.put(phrase, result);
                    sendMessage(channel,"Added response to phrase: " + phrase);
                    break;
            }
        } catch (SQLException ex) {
            if (ex.getMessage().contains("[SQLITE_CONSTRAINT_UNIQUE]")) {
                sendMessage(channel,type + ": `"+split[1]+"` already exists");
            } else {
                error("Failed to insert "+type+": " + message.split(" ")[1], ex);
                sendMessage(channel,"Failed to add "+type+": " + ex.toString());
            }
        } catch (IndexOutOfBoundsException ex) {
            warn("add " + type + " command malformatted");
            switch (type) {
                case "sfx":
                    sendMessage(channel,"Malformatted command; Usage: `!janna.addSfx <phrase> <https://__________>` (wav, mp3, ogg)");
                    break;
                case "filter":
                    sendMessage(channel,"Malformatted command; Usage: `!janna.addFilter <phrase> <filtered phrase>`");
                    break;
                case "response":
                    sendMessage(channel,"Malformatted command; Usage: `!janna.addResponse <phrase> <response>`");
                    break;
            }
        }
    }

    public void removeReaction (String type, String message, String channel) {
        String[] split = message.split(" ",3);
        String phrase = "";
        try {
            phrase = split[1].toLowerCase();
            String query = "DELETE FROM reaction WHERE type='"+type+"' AND phrase='"+phrase+"';";
            if (executeUpdate(query) > 0) {
                switch (type) {
                    case "sfx":
                        sfxList.remove(phrase);
                        sendMessage(channel,"Removed SFX for phrase: " + phrase);
                        break;
                    case "filter":
                        filterList.remove(phrase);
                        sendMessage(channel,"Removed filter for phrase: " + phrase);
                        break;
                    case "response":
                        responseList.remove(phrase);
                        sendMessage(channel,"Removed response to phrase: " + phrase);
                        break;
                }
            } else {
                sendMessage(channel,"No " + type + " found " + (type.equals("response") ? "to" : "for") + " phrase: " + phrase);
            }
        } catch (SQLException ex) {
            error("Failed to remove "+type+": " + message.split(" ")[1], ex);
            sendMessage(channel,"Failed to remove "+type+": " + ex.toString());
        } catch (IndexOutOfBoundsException ex) {
            warn("remove " + type + " command malformatted");
            switch (type) {
                case "sfx":
                    sendMessage(channel,"Malformatted command; Usage: `!janna.removeSfx <phrase>");
                    break;
                case "filter":
                    sendMessage(channel,"Malformatted command; Usage: `!janna.removeFilter <phrase>`");
                    break;
                case "response":
                    sendMessage(channel,"Malformatted command; Usage: `!janna.removeResponse <phrase> `");
                    break;
            }
        }
    }

    public boolean isSuperMod(String username) {
        return ("1".equals(getUser(username).roles.get("broadcaster")));
    }

    public boolean isMod(String username) {
        return (isSuperMod(username) || "1".equals(getUser(username).roles.get("moderator")));
    }

    public boolean isSub(String username) {
        return (isSuperMod(username)
                || "1".equals(getUser(username).roles.get("subscriber"))
                || "1".equals(getUser(username).roles.get("founder"))
        );
    }

    public boolean isVIP(String username) {
        return (isSuperMod(username) || isMod(username) || "1".equals(getUser(username).roles.get("vip")));
    }

    public List<String> getMods() {
        return twitch.getMessagingInterface().getChatters(appConfig.get("mainchannel")).execute().getModerators();
    }
}





