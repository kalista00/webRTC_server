package com.example.demo;


import com.example.demo.bean.DeviceSession;
import com.example.demo.bean.EventData;
import com.example.demo.bean.RoomInfo;
import com.example.demo.bean.UserBean;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.websocket.OnClose;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.example.demo.MemCons.rooms;

@ServerEndpoint("/ws/{userId}/{device}")
@Component
public class WebSocketServer {
    private static final Logger LOG = LoggerFactory.getLogger(WebSocketServer.class);

    private String userId;
    private static Gson gson = new Gson();
    private static String avatar = "p1.jpeg";


    // 사용자 userId가 로그인했을 때    @OnOpen
    public void onOpen(Session session, @PathParam("userId") String userId, @PathParam("device") String de) {
        int device = Integer.parseInt(de);
        UserBean userBean = MemCons.userBeans.get(userId);
        if (userBean == null) {
            userBean = new UserBean(userId, avatar);
        }
        if (device == 0) {
            userBean.setPhoneSession(session, device);
            userBean.setPhone(true);
            LOG.info("Phone用户登陆:" + userBean.getUserId() + ",session:" + session.getId());
        } else {
            userBean.setPcSession(session, device);
            userBean.setPhone(false);
            LOG.info("PC用户登陆:" + userBean.getUserId() + ",session:" + session.getId());
        }
        this.userId = userId;

        //리스트에 추가합니다.
        MemCons.userBeans.put(userId, userBean);

        //로그인 성공 후, 개인 정보를 반환합니다.
        EventData send = new EventData();
        send.setEventName("__login_success");
        Map<String, Object> map = new HashMap<>();
        map.put("userID", userId);
        map.put("avatar", avatar);
        send.setData(map);
        session.getAsyncRemote().sendText(gson.toJson(send));


    }

    //사용자가 로그아웃하거나 연결이 끊겼을 때
    @OnClose
    public void onClose() {
        System.out.println(userId + "-->onClose......");
        //사용자 이름으로부터 방을 찾습니다.
        UserBean userBean = MemCons.userBeans.get(userId);
        if (userBean != null) {
            if (userBean.isPhone()) {
                Session phoneSession = userBean.getPhoneSession();
                if (phoneSession != null) {
                    try {
                        phoneSession.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    userBean.setPhoneSession(null, 0);
                    MemCons.userBeans.remove(userId);
                }
                LOG.info("Phone用户离开:" + userBean.getUserId());
            } else {
                Session pcSession = userBean.getPcSession();
                if (pcSession != null) {
                    try {
                        pcSession.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    userBean.setPcSession(null, 0);
                    MemCons.userBeans.remove(userId);
                    LOG.info("PC用户离开:" + userBean.getUserId());
                }
            }
        }

    }

    @OnMessage
    public void onMessage(String message, Session session) {
        System.out.println("receive data:" + message);
        handleMessage(message, session);
    }

    //다양한 종류의 메시지를 전송합니다.
    private void handleMessage(String message, Session session) {
        EventData data;
        try {
            data = gson.fromJson(message, EventData.class);
        } catch (JsonSyntaxException e) {
            System.out.println("json解析错误：" + message);
            return;
        }
        switch (data.getEventName()) {
            case "__create":
                createRoom(message, data.getData());
                break;
            case "__invite":
                invite(message, data.getData());
                break;
            case "__ring":
                ring(message, data.getData());
                break;
            case "__cancel":
                cancel(message, data.getData());
                break;
            case "__reject":
                reject(message, data.getData());
                break;
            case "__join":
                join(message, data.getData());
                break;
            case "__ice_candidate":
                iceCandidate(message, data.getData());
                break;
            case "__offer":
                offer(message, data.getData());
                break;
            case "__answer":
                answer(message, data.getData());
                break;
            case "__leave":
                leave(message, data.getData());
                break;
            case "__audio":
                transAudio(message, data.getData());
                break;
            case "__disconnect":
                disconnet(message, data.getData());
                break;
            default:
                break;
        }

    }

    // 방을 생성합니다.
    private void createRoom(String message, Map<String, Object> data) {
        String room = (String) data.get("room");
        String userId = (String) data.get("userID");

        System.out.println(String.format("createRoom:%s ", room));

        RoomInfo roomParam = rooms.get(room);
        //해당 방이 존재하지 않을 때
        if (roomParam == null) {
            int size = (int) Double.parseDouble(String.valueOf(data.get("roomSize")));
            //방을 생성합니다.
            RoomInfo roomInfo = new RoomInfo();
            roomInfo.setMaxSize(size);
            roomInfo.setRoomId(room);
            roomInfo.setUserId(userId);
            //방 정보를 저장합니다.
            rooms.put(room, roomInfo);


            CopyOnWriteArrayList<UserBean> copy = new CopyOnWriteArrayList<>();
            //자신을 방에 추가합니다.
            UserBean my = MemCons.userBeans.get(userId);
            copy.add(my);
            rooms.get(room).setUserBeans(copy);

            //자신에게 메시지를 보냅니다.
            EventData send = new EventData();
            send.setEventName("__peers");
            Map<String, Object> map = new HashMap<>();
            map.put("connections", "");
            map.put("you", userId);
            map.put("roomSize", size);
            send.setData(map);
            System.out.println(gson.toJson(send));
            sendMsg(my, -1, gson.toJson(send));

        }

    }

    //최초 초대 메시지를 처리합니다.
    private void invite(String message, Map<String, Object> data) {
        String userList = (String) data.get("userList");
        String room = (String) data.get("room");
        String inviteId = (String) data.get("inviteID");
        boolean audioOnly = (boolean) data.get("audioOnly");
        String[] users = userList.split(",");

        System.out.println(String.format("room:%s,%s invite %s audioOnly:%b", room, inviteId, userList, audioOnly));
        //다른 사용자에게 초대 메시지를 보냅니다.
        for (String user : users) {
            UserBean userBean = MemCons.userBeans.get(user);
            if (userBean != null) {
                sendMsg(userBean, -1, message);
            }
        }


    }

 // 벨소리 응답
    private void ring(String message, Map<String, Object> data) {
        String room = (String) data.get("room");
        String inviteId = (String) data.get("toID");

        UserBean userBean = MemCons.userBeans.get(inviteId);
        if (userBean != null) {
            sendMsg(userBean, -1, message);
        }
    }

    // 통화 취소
    private void cancel(String message, Map<String, Object> data) {
        String room = (String) data.get("room");
        String userList = (String) data.get("userList");
        String[] users = userList.split(",");
        for (String userId : users) {
            UserBean userBean = MemCons.userBeans.get(userId);
            if (userBean != null) {
                sendMsg(userBean, -1, message);
            }
        }

        if (MemCons.rooms.get(room) != null) {
            MemCons.rooms.remove(room);
        }
    }

    // 통화 거부
    private void reject(String message, Map<String, Object> data) {
        String room = (String) data.get("room");
        String toID = (String) data.get("toID");
        UserBean userBean = MemCons.userBeans.get(toID);
        if (userBean != null) {
            sendMsg(userBean, -1, message);
        }
        RoomInfo roomInfo = MemCons.rooms.get(room);
        if (roomInfo != null) {
            if (roomInfo.getMaxSize() == 2) {
                MemCons.rooms.remove(room);
            }
        }
    }

    // 방 참여
    private void join(String message, Map<String, Object> data) {
        String room = (String) data.get("room");
        String userID = (String) data.get("userID");

        RoomInfo roomInfo = rooms.get(room);

        int maxSize = roomInfo.getMaxSize();
        CopyOnWriteArrayList<UserBean> roomUserBeans = roomInfo.getUserBeans();

        // 방이 이미 가득 찼을 경우
        if (roomUserBeans.size() >= maxSize) {
            return;
        }
        UserBean my = MemCons.userBeans.get(userID);
        // 1. 나 자신을 방에 추가
        roomUserBeans.add(my);
        roomInfo.setUserBeans(roomUserBeans);
        rooms.put(room, roomInfo);

        // 2. 방에 있는 모든 사람 정보 반환
        EventData send = new EventData();
        send.setEventName("__peers");
        Map<String, Object> map = new HashMap<>();

        String[] cons = new String[roomUserBeans.size()];
        for (int i = 0; i < roomUserBeans.size(); i++) {
            UserBean userBean = roomUserBeans.get(i);
            if (userBean.getUserId().equals(userID)) {
                continue;
            }
            cons[i] = userBean.getUserId();
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cons.length; i++) {
            if (cons[i] == null) {
                continue;
            }
            sb.append(cons[i]).append(",");
        }
        if (sb.length() > 0) {
            sb.deleteCharAt(sb.length() - 1);
        }

        // 나에게 메시지 전송
        map.put("connections", sb.toString());
        map.put("you", userID);
        map.put("roomSize", roomInfo.getMaxSize());
        send.setData(map);
        sendMsg(my, -1, gson.toJson(send));

        // 3. 방에 있는 다른 사람들에게 메시지 전송
        EventData newPeer = new EventData();
        newPeer.setEventName("__new_peer");
        Map<String, Object> sendMap = new HashMap<>();
        sendMap.put("userID", userID);
        newPeer.setData(sendMap);
        for (UserBean userBean : roomUserBeans) {
            if (userBean.getUserId().equals(userID)) {
                continue;
            }
            sendMsg(userBean, -1, gson.toJson(newPeer));
        }
    }

    // 음성 통화로 전환
    private void transAudio(String message, Map<String, Object> data) {
        String userId = (String) data.get("userID");
        UserBean userBean = MemCons.userBeans.get(userId);
        if (userBean == null) {
            System.out.println("사용자 " + userId + "가 존재하지 않습니다.");
            return;
        }
        sendMsg(userBean, -1, message);
    }

    // 의도하지 않은 연결 해제
    private void disconnet(String message, Map<String, Object> data) {
        String userId = (String) data.get("userID");
        UserBean userBean = MemCons.userBeans.get(userId);
        if (userBean == null) {
            System.out.println("사용자 " + userId + "가 존재하지 않습니다.");
            return;
        }
        sendMsg(userBean, -1, message);
    }

    // Offer 메시지 전송
    private void offer(String message, Map<String, Object> data) {
        String userId = (String) data.get("userID");
        UserBean userBean = MemCons.userBeans.get(userId);
        sendMsg(userBean, -1, message);
    }

    // Answer 메시지 전송
    private void answer(String message, Map<String, Object> data) {
        String userId = (String) data.get("userID");
        UserBean userBean = MemCons.userBeans.get(userId);
        if (userBean == null) {
            System.out.println("사용자 " + userId + "가 존재하지 않습니다.");
            return;
        }
        sendMsg(userBean, -1, message);
    }

    // ICE 후보 정보 전송
    private void iceCandidate(String message, Map<String, Object> data) {
        String userId = (String) data.get("userID");
        UserBean userBean = MemCons.userBeans.get(userId);
        if (userBean == null) {
            System.out.println("사용자 " + userId + "가 존재하지 않습니다.");
            return;
        }
        sendMsg(userBean, -1, message);
    }

    // 방 나가기
    private void leave(String message, Map<String, Object> data) {
        String room = (String) data.get("room");
        String userId = (String) data.get("fromID");
        if (userId == null) return;
        // 방 정보 가져오기
        RoomInfo roomInfo = MemCons.rooms.get(room);
        // 방의 사용자 목록 가져오기
        CopyOnWriteArrayList<UserBean> roomInfoUserBeans = roomInfo.getUserBeans();
        // 방의 다른 사람들에게 나간 사용자를 알립니다.
        for (UserBean userBean : roomInfoUserBeans) {
            // 자신을 제외하고 메시지 보내기
            if (userId.equals(userBean.getUserId())) {
                roomInfoUserBeans.remove(userBean);
                continue;
            }
            // 메시지 전송
            sendMsg(userBean, -1, message);
        }

        if (roomInfoUserBeans.size() == 1) {
            System.out.println("방에 사람이 한 명만 남았습니다.");
            if (roomInfo.getMaxSize() == 2) {
                MemCons.rooms.remove(room);
            }
        }

        if (roomInfoUserBeans.size() == 0) {
            System.out.println("방에 사람이 없습니다.");
            MemCons.rooms.remove(room);
        }
    }

    private static final Object object = new Object();

    // 다른 디바이스로 메시지 전송
    private void sendMsg(UserBean userBean, int device, String str) {
        if (device == 0) {
            Session phoneSession = userBean.getPhoneSession();
            if (phoneSession != null) {
                synchronized (object) {
                    phoneSession.getAsyncRemote().sendText(str);
                }
            }
        } else if (device == 1) {
            Session pcSession = userBean.getPcSession();
            if (pcSession != null) {
                synchronized (object) {
                    pcSession.getAsyncRemote().sendText(str);
                }
            }
        } else {
            Session phoneSession = userBean.getPhoneSession();
            boolean sent = false;
            boolean exception = false;
            while (!sent) {
                if (phoneSession != null) {
                    synchronized (object) {
                        try {
                            phoneSession.getAsyncRemote().sendText(str);
                            sent = true;
                            exception = false;
                        } catch (IllegalStateException error) {
                            System.out.println("TEST--TEST:" + error);
                            exception = true;
                        }
                    }
                    if (exception) {
                        try {
                            Thread.sleep(10);
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }
            }
            Session pcSession = userBean.getPcSession();
            if (pcSession != null) {
                synchronized (object) {
                    pcSession.getAsyncRemote().sendText(str);
                }
            }
        }
    }
}