package io.anuke.mindustry.net;

import io.anuke.arc.*;
import io.anuke.arc.collection.*;
import io.anuke.arc.function.*;
import io.anuke.arc.net.*;
import io.anuke.arc.util.*;
import io.anuke.arc.util.async.*;
import io.anuke.arc.util.pooling.*;
import io.anuke.mindustry.net.Net.*;
import io.anuke.mindustry.net.Packets.*;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.concurrent.*;

import static io.anuke.mindustry.Vars.*;

public class ArcNetImpl implements NetProvider{
    final Client client;
    final Supplier<DatagramPacket> packetSupplier = () -> new DatagramPacket(new byte[256], 256);

    final Server server;
    final CopyOnWriteArrayList<ArcConnection> connections = new CopyOnWriteArrayList<>();
    Thread serverThread;

    public ArcNetImpl(){
        client = new Client(8192, 4096, new PacketSerializer());
        client.setDiscoveryPacket(packetSupplier);
        client.addListener(new NetListener(){
            @Override
            public void connected(Connection connection){
                Connect c = new Connect();
                c.addressTCP = connection.getRemoteAddressTCP().getAddress().getHostAddress();
                c.id = connection.getID();
                if(connection.getRemoteAddressTCP() != null) c.addressTCP = connection.getRemoteAddressTCP().toString();

                Core.app.post(() -> net.handleClientReceived(c));
            }

            @Override
            public void disconnected(Connection connection, DcReason reason){
                if(connection.getLastProtocolError() != null){
                    netClient.setQuiet();
                }

                Disconnect c = new Disconnect();
                c.reason = reason.toString();
                Core.app.post(() -> net.handleClientReceived(c));
            }

            @Override
            public void received(Connection connection, Object object){
                if(object instanceof FrameworkMessage) return;

                Core.app.post(() -> {
                    try{
                        net.handleClientReceived(object);
                    }catch(Exception e){
                        handleException(e);
                    }
                });

            }
        });

        server = new Server(4096 * 2, 4096, new PacketSerializer());
        server.setMulticast(multicastGroup, multicastPort);
        server.setDiscoveryHandler((address, handler) -> {
            ByteBuffer buffer = NetworkIO.writeServerData();
            buffer.position(0);
            handler.respond(buffer);
        });

        server.addListener(new NetListener(){

            @Override
            public void connected(Connection connection){
                String ip = connection.getRemoteAddressTCP().getAddress().getHostAddress();

                ArcConnection kn = new ArcConnection(ip, connection);

                Connect c = new Connect();
                c.id = kn.id;
                c.addressTCP = ip;

                Log.debug("&bRecieved connection: {0}", c.addressTCP);

                connections.add(kn);
                Core.app.post(() -> net.handleServerReceived(kn, c));
            }

            @Override
            public void disconnected(Connection connection, DcReason reason){
                ArcConnection k = getByArcID(connection.getID());
                if(k == null) return;

                Disconnect c = new Disconnect();
                c.id = k.id;
                c.reason = reason.toString();

                Core.app.post(() -> {
                    net.handleServerReceived(k, c);
                    connections.remove(k);
                });
            }

            @Override
            public void received(Connection connection, Object object){
                ArcConnection k = getByArcID(connection.getID());
                if(object instanceof FrameworkMessage || k == null) return;

                Core.app.post(() -> {
                    try{
                        net.handleServerReceived(k, object);
                    }catch(RuntimeException e){
                        if(e.getCause() instanceof ValidateException){
                            ValidateException v = (ValidateException)e.getCause();
                            Log.err("Validation failed: {0} ({1})", v.player.name, v.getMessage());
                        }else{
                            e.printStackTrace();
                        }
                    }catch(Exception e){
                        e.printStackTrace();
                    }
                });
            }
        });
    }

    private static boolean isLocal(InetAddress addr){
        if(addr.isAnyLocalAddress() || addr.isLoopbackAddress()) return true;

        try{
            return NetworkInterface.getByInetAddress(addr) != null;
        }catch(Exception e){
            return false;
        }
    }

    @Override
    public void connectClient(String ip, int port, Runnable success){
        Threads.daemon(() -> {
            try{
                //just in case
                client.stop();

                Threads.daemon("Net Client", () -> {
                    try{
                        client.run();
                    }catch(Exception e){
                        if(!(e instanceof ClosedSelectorException)) handleException(e);
                    }
                });

                client.connect(5000, ip, port, port);
                success.run();
            }catch(Exception e){
                handleException(e);
            }
        });
    }

    @Override
    public void disconnectClient(){
        client.close();
    }

    @Override
    public void sendClient(Object object, SendMode mode){
        try{
            if(mode == SendMode.tcp){
                client.sendTCP(object);
            }else{
                client.sendUDP(object);
            }
            //sending things can cause an under/overflow, catch it and disconnect instead of crashing
        }catch(BufferOverflowException | BufferUnderflowException e){
            net.showError(e);
        }

        Pools.free(object);
    }

    @Override
    public void pingHost(String address, int port, Consumer<Host> valid, Consumer<Exception> invalid){
        Threads.daemon(() -> {
            try{
                DatagramSocket socket = new DatagramSocket();
                socket.send(new DatagramPacket(new byte[]{-2, 1}, 2, InetAddress.getByName(address), port));
                socket.setSoTimeout(2000);

                DatagramPacket packet = packetSupplier.get();
                socket.receive(packet);

                ByteBuffer buffer = ByteBuffer.wrap(packet.getData());
                Host host = NetworkIO.readServerData(packet.getAddress().getHostAddress(), buffer);

                Core.app.post(() -> valid.accept(host));
            }catch(Exception e){
                Core.app.post(() -> invalid.accept(e));
            }
        });
    }

    @Override
    public void discoverServers(Consumer<Host> callback, Runnable done){
        Array<InetAddress> foundAddresses = new Array<>();
        client.discoverHosts(port, multicastGroup, multicastPort, 3000, packet -> {
            Core.app.post(() -> {
                try{
                    if(foundAddresses.contains(address -> address.equals(packet.getAddress()) || (isLocal(address) && isLocal(packet.getAddress())))){
                        return;
                    }
                    ByteBuffer buffer = ByteBuffer.wrap(packet.getData());
                    Host host = NetworkIO.readServerData(packet.getAddress().getHostAddress(), buffer);
                    callback.accept(host);
                    foundAddresses.add(packet.getAddress());
                }catch(Exception e){
                    //don't crash when there's an error pinging a a server or parsing data
                    e.printStackTrace();
                }
            });
        }, () -> Core.app.post(done));
    }

    @Override
    public void dispose(){
        try{
            client.dispose();
        }catch(IOException e){
            throw new RuntimeException(e);
        }
    }

    @Override
    public Iterable<ArcConnection> getConnections(){
        return connections;
    }

    @Override
    public void sendServerStream(int id, Streamable stream){
        ArcConnection connection = (ArcConnection)getConnection(id);
        if(connection == null) return;

        connection.connection.addListener(new InputStreamSender(stream.stream, 512){
            int id;

            @Override
            protected void start(){
                //send an object so the receiving side knows how to handle the following chunks
                StreamBegin begin = new StreamBegin();
                begin.total = stream.stream.available();
                begin.type = Registrator.getID(stream.getClass());
                connection.connection.sendTCP(begin);
                id = begin.id;
            }

            @Override
            protected Object next(byte[] bytes){
                StreamChunk chunk = new StreamChunk();
                chunk.id = id;
                chunk.data = bytes;
                return chunk; //wrap the byte[] with an object so the receiving side knows how to handle it.
            }
        });
    }

    @Override
    public void hostServer(int port) throws IOException{
        connections.clear();
        server.bind(port, port);

        serverThread = new Thread(() -> {
            try{
                server.run();
            }catch(Throwable e){
                if(!(e instanceof ClosedSelectorException)) Threads.throwAppException(e);
            }
        }, "Net Server");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    @Override
    public void closeServer(){
        connections.clear();
        Threads.daemon(server::stop);
    }

    ArcConnection getByArcID(int id){
        for(int i = 0; i < connections.size(); i++){
            ArcConnection con = connections.get(i);
            if(con.connection != null && con.connection.getID() == id){
                return con;
            }
        }

        return null;
    }

    class ArcConnection extends NetConnection{
        public final Connection connection;

        public ArcConnection(String address, Connection connection){
            super(address);
            this.connection = connection;
        }

        @Override
        public boolean isConnected(){
            return connection.isConnected();
        }

        @Override
        public void send(Object object, SendMode mode){
            try{
                if(mode == SendMode.tcp){
                    connection.sendTCP(object);
                }else{
                    connection.sendUDP(object);
                }
            }catch(Exception e){
                Log.err(e);
                Log.info("Error sending packet. Disconnecting invalid client!");
                connection.close(DcReason.error);

                ArcConnection k = getByArcID(connection.getID());
                if(k != null) connections.remove(k);
            }
        }

        @Override
        public void close(){
            if(connection.isConnected()) connection.close(DcReason.closed);
        }
    }

    private void handleException(Exception e){
        if(e instanceof ArcNetException){
            Core.app.post(() -> net.showError(new IOException("mismatch")));
        }else if(e instanceof ClosedChannelException){
            Core.app.post(() -> net.showError(new IOException("alreadyconnected")));
        }else{
            Core.app.post(() -> net.showError(e));
        }
    }

}