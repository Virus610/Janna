package com.project610.commands;

import com.github.twitch4j.helix.domain.Clip;
import com.github.twitch4j.helix.domain.ClipList;
import com.github.twitch4j.helix.domain.Follow;
import com.github.twitch4j.helix.domain.FollowList;
import com.github.twitch4j.util.PaginationUtil;
import com.project610.Auth;
import com.project610.Creds;
import com.project610.Janna;
import org.checkerframework.checker.units.qual.A;

import java.util.*;

import static com.project610.Janna.*;

public class GetClip extends Command {

    @Override
    public Object apply(Object o) {
        super.apply(o);

        // Handle args
        if (split.length == 1) {
            sendMessage(channel, "Usage: !clip <searchTerm> [channel:channelName] [by:user] [game:gameTitle]");
            return 1;
        }

        ArrayList<String> searchTerms = new ArrayList<>();
        String fullSearch = "";
        String channelId = mainchannel_user.getId(), clippedBy = "", game = "";
        for (int i = 1; i < split.length; i++) {
            String temp = split[i].toLowerCase();
            if (temp.startsWith("channel:")) {
                try {
                    channelId = getChannelId(temp.split(":")[1]);
                    System.out.println("id: " + channelId);
                } catch (Exception ex) {
                    sendMessage(channel, "Couldn't get clips for " + temp);
                    error("Failed to get channelID for " + temp, ex);
                    return 1;
                }
            } else if (temp.startsWith("by:")) {
                clippedBy = temp.split(":")[1];
            } else if (temp.startsWith("game:")) {
                game = temp.split(":")[1];
            } else {
                searchTerms.add(temp);
                fullSearch += temp + " ";
            }
        }
        fullSearch = fullSearch.trim();

        if (fullSearch.isEmpty() && !game.isEmpty()) {
            sendMessage(channel, "Need at least 1 Clip Title search term if you want to filter by game (Blame Twitch)");
            return 1;
        }

        // Gather all clips
        LinkedHashSet<Clip> clips = new LinkedHashSet<>();
        int limit = 100, results = -1;
        String cursor = null;

        while (results != clips.size()) {
            ClipList clipList = twitch.getHelix().getClips(Creds._helixtoken, channelId, null, null, cursor, null, limit, null, null).execute();
            if (cursor != null && cursor.equalsIgnoreCase(clipList.getPagination().getCursor())) break;
            cursor = clipList.getPagination().getCursor();
            results = clips.size();
            clips.addAll(clipList.getData());
        }
        System.out.println("Size: " + clips.size());

        // Filter out stuff
        if (!clippedBy.isEmpty()) {
            LinkedHashSet<Clip> userClips = new LinkedHashSet<>();
            for (Clip clip : clips) {
                if (clip.getCreatorName().toLowerCase().equalsIgnoreCase(clippedBy)) {
                    userClips.add(clip);
                }
            }
            if (userClips.size() == 0) {
                for (Clip clip : clips) {
                    if (clip.getCreatorName().toLowerCase().contains(clippedBy)) {
                        userClips.add(clip);
                    }
                }
                if (userClips.size() == 0) {
                    sendMessage(channel, "No clips matching by:"+clippedBy);
                    return 1;
                }
            }
            clips.retainAll(userClips);
            System.out.println("Size: " + clips.size());
        }

        // Try to find clip with increasing lenience

        // Starting with if all non-channel-name search terms strung together are found as one string
        ArrayList<Clip> foundClips = new ArrayList<>();
        if (!fullSearch.isEmpty()) {
            for (Clip clip : clips) {
                if (clip.getTitle().toLowerCase().contains(fullSearch)) {
                    foundClips.add(clip);
                }
            }

            if (foundClips.size() == 0) {
                // No luck? Let's try if all terms show up in any order
                for (Clip clip : clips) {
                    for (String term : searchTerms) {
                        if (!clip.getTitle().toLowerCase().contains(term)) {
                            continue;
                        }
                        foundClips.add(clip);
                    }
                }
            }

            if (foundClips.size() == 0) {
                // STILL No luck? Let's try if individual terms show up
                // This is hacky, but adding weight to clips with more matching terms by adding them to the list multiple times
                for (Clip clip : clips) {
                    for (String term : searchTerms) {
                        if (clip.getTitle().toLowerCase().contains(term)) {
                            foundClips.add(clip);
                        }
                    }
                }
            }

            // If after all this, we've got nothing, maybe just give up
            if (foundClips.size() == 0) {
                sendMessage(channel, "Couldn't find any clips, sorry!");
                return 1;
            }
        } else {
            foundClips = new ArrayList<>(clips);
        }

        // TODO: Add support for multi-word game titles
        if (!game.isEmpty()) {
            HashSet<Clip> clipSet = new HashSet<>(foundClips);
            int numClips = clipSet.size(), clipLimit = 30;
            if (numClips > clipLimit) {
                sendMessage(channel, "Too many results (" + numClips + "/" + clipLimit + ") to filter by game, try narrowing down your search a bit...");
                return 1;
            }

            LinkedHashSet<Clip> gameClips = new LinkedHashSet<>();
            for (Clip clip : clipSet) {
                String gameName = twitch.getHelix().getGames(Creds._helixtoken, Arrays.asList(clip.getGameId()), null, null).execute().getGames().get(0).getName().toLowerCase();
                if (gameName.contains(game)) {
                    gameClips.add(clip);
                }
            }
            if (gameClips.size() == 0) {
                sendMessage(channel, "No clips matching game:"+game);
                return 1;
            }
            foundClips.retainAll(gameClips);
        }

        sendMessage(channel, getRandomClip(foundClips).getUrl());
        return 1;
    }

    private Clip getRandomClip(ArrayList<Clip> foundClips) {
        return foundClips.get((int)(Math.random() * foundClips.size()));
    }

    private String getChannelId(String channelName) {
        return twitch.getHelix().getUsers(Creds._helixtoken, null, Arrays.asList(channelName)).execute().getUsers().get(0).getId();
    }
}