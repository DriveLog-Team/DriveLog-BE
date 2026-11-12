package com.drivelog.media;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

class HandoffTest {
    @TempDir Path dir;

    @Test void phoneDecodesQrAndOnlyOneConcurrentRequestWins() throws Exception {
        var media = mock(MediaJobController.class);
        var job = mock(MediaJobController.Job.class);
        var req = new MockHttpServletRequest();
        var jobId = UUID.randomUUID();
        var path = Files.writeString(dir.resolve("moment-002.mp4"), "selected clip");
        when(media.owned(jobId,req)).thenReturn(job);
        when(media.clip(job,"moment-002")).thenReturn(path);
        var controller = new HandoffController(media, "https://phone.example", Duration.ofMinutes(5));
        var ticket = controller.issue(jobId, "moment-002", req).getBody();
        URI qr = decode(ticket);
        assertThat(qr.getAuthority()).isEqualTo("phone.example");
        assertThat(qr.getPath()).isEqualTo("/api/handoffs/open");
        String[] parts = qr.getFragment().split("\\.");
        assertThat(parts[0]).isEqualTo(ticket.id().toString());
        assertThatThrownBy(() -> controller.redeem(ticket.id(),"wrong")).isInstanceOf(ResponseStatusException.class);
        Callable<Integer> save = () -> {
            try { return controller.redeem(ticket.id(),parts[1]).getStatusCode().value(); }
            catch (ResponseStatusException e) { return e.getStatusCode().value(); }
        };
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(save);
            var b = pool.submit(save);
            assertThat(java.util.List.of(a.get(),b.get())).containsExactlyInAnyOrder(200,410);
        }
    }

    @Test void changedPermissionsRevocationAndExpirationPreventDownload() throws Exception {
        var media = mock(MediaJobController.class);
        var req = new MockHttpServletRequest();
        var job = mock(MediaJobController.Job.class);
        UUID id = UUID.randomUUID();
        when(media.owned(id,req)).thenReturn(job);
        when(media.clip(job,"moment-001")).thenReturn(dir.resolve("clip.mp4"));
        var controller = new HandoffController(media,"https://phone.example",Duration.ofMinutes(5));
        var ticket = controller.issue(id,"moment-001",req).getBody();
        String code = decode(ticket).getFragment().split("\\.")[1];
        when(media.clip(job,"moment-001")).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertThatExceptionOfType(ResponseStatusException.class).isThrownBy(() -> controller.redeem(ticket.id(),code))
                .extracting(e -> e.getStatusCode().value()).isEqualTo(403);
        controller.revoke(ticket.id(),req);
        assertThatExceptionOfType(ResponseStatusException.class).isThrownBy(() -> controller.redeem(ticket.id(),code))
                .extracting(e -> e.getStatusCode().value()).isEqualTo(410);
        var expiring = new HandoffController(media,"https://phone.example",Duration.ofNanos(1));
        var expired = expiring.issue(id,"moment-002",req).getBody();
        String expiredCode = decode(expired).getFragment().split("\\.")[1];
        assertThatExceptionOfType(ResponseStatusException.class).isThrownBy(() -> expiring.redeem(expired.id(),expiredCode))
                .extracting(e -> e.getStatusCode().value()).isEqualTo(410);
    }

    private static URI decode(HandoffController.Ticket ticket) throws Exception {
        byte[] png = Base64.getDecoder().decode(ticket.qrImage().split(",",2)[1]);
        var image = ImageIO.read(new ByteArrayInputStream(png));
        int w = image.getWidth(), h = image.getHeight();
        var luminance = new RGBLuminanceSource(w,h,image.getRGB(0,0,w,h,null,0,w));
        return URI.create(new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(luminance))).getText());
    }
}
