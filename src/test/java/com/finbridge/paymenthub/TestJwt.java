package com.finbridge.paymenthub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.springframework.test.context.DynamicPropertyRegistry;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.*;

/** External issuer fixture: ephemeral RSA keys, actual JWKS HTTP and signed Bearer JWTs. No decoder mocks. */
final class TestJwt {
    static final String A="00000000-0000-4000-8000-000000000011", B="00000000-0000-4000-8000-000000000012";
    static final String ADMIN="00000000-0000-4000-8000-000000000092", OPS="00000000-0000-4000-8000-000000000091";
    static final String AUDITOR="00000000-0000-4000-8000-000000000093";
    static final String ISSUER="https://finbridge.test/issuer", AUDIENCE="finbridge-api";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final KeyPair KEYS = keys(), OTHER = keys();
    private static final HttpServer SERVER = server();
    private static KeyPair keys() {
        try { var gen=KeyPairGenerator.getInstance("RSA"); gen.initialize(2048); return gen.generateKeyPair(); }
        catch (GeneralSecurityException ex) { throw new IllegalStateException(ex); }
    }
    private static String b64(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static String unsigned(java.math.BigInteger n) {
        byte[] bytes=n.toByteArray(); return b64(bytes[0]==0 ? Arrays.copyOfRange(bytes,1,bytes.length):bytes);
    }
    private static HttpServer server() {
        try {
            var pub=(RSAPublicKey)KEYS.getPublic();
            byte[] jwks=JSON.writeValueAsBytes(Map.of("keys",List.of(Map.of("kty","RSA","kid","test-key","use","sig","alg","RS256","n",unsigned(pub.getModulus()),"e",unsigned(pub.getPublicExponent())))));
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/jwks", exchange -> {
                exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(200,jwks.length);
                try(var out=exchange.getResponseBody()){out.write(jwks);}
            });
            // HttpServer inherits daemon status from its starter; do not hold the test JVM alive.
            Thread t=new Thread(server::start); t.setDaemon(true); t.start(); t.join();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
            return server;
        } catch(Exception e){throw new IllegalStateException(e);}
    }
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("finbridge.security.issuer",()->ISSUER);
        registry.add("finbridge.security.audience",()->AUDIENCE);
        registry.add("finbridge.security.jwk-set-uri",()->"http://127.0.0.1:"+SERVER.getAddress().getPort()+"/jwks");
    }
    static String bearer(String subject,String role){return issue(subject,role,ISSUER,AUDIENCE,Instant.now().plusSeconds(600),false);}
    static String issue(String subject,String role,String issuer,String audience,Instant expiration,boolean wrongKey){
        try {
            Map<String,Object> claims=new HashMap<>(); claims.put("sub",subject); claims.put("iss",issuer);
            claims.put("aud",List.of(audience)); claims.put("iat",Instant.now().minusSeconds(600).getEpochSecond());
            if(expiration!=null)claims.put("exp",expiration.getEpochSecond()); claims.put("roles",List.of(role));
            String input=b64(JSON.writeValueAsBytes(Map.of("alg","RS256","kid","test-key","typ","JWT")))+"."+b64(JSON.writeValueAsBytes(claims));
            Signature signature=Signature.getInstance("SHA256withRSA"); signature.initSign((wrongKey?OTHER:KEYS).getPrivate());
            signature.update(input.getBytes(StandardCharsets.US_ASCII)); return "Bearer "+input+"."+b64(signature.sign());
        } catch(Exception e){throw new IllegalStateException(e);}
    }
}
