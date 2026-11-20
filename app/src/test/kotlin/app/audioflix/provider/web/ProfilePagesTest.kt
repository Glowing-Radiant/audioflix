package app.audioflix.provider.web

import app.audioflix.provider.RemoteEntry
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Fixtures mirror the shape of a profile page and an upload page.
class ProfilePagesTest {
  private val profileHtml = """
    <!DOCTYPE html><html><body>
    <header><a href="/" class="logo">Logo</a>
      <nav><a href="https://soundgasm.net/">Home</a><a href="https://soundgasm.net/login">Login</a></nav></header>
    <div class="sound-details"><a href="https://soundgasm.net/u/someone/First-One">[Tag] First one</a></br>
      <span class="soundDescription">A description</span></br><span class="playCount">Play Count: 695</span></div>
    <div class="sound-details"><a href="https://soundgasm.net/u/someone/Second">Second</a></br>
      <span class="playCount">Play Count: 12</span></div>
    <div class="sound-details"><a href="https://soundgasm.net/u/someone/Second">Second</a></div>
    </body></html>
  """.trimIndent()

  private val uploadHtml = """
    <!DOCTYPE html><html><body>
    <div style="margin:10px 0"><a href="https://soundgasm.net/u/someone">someone</a></div>
    <div class="jp-details"><div class="jp-title" aria-label="title">Verification</div></div>
    <script type="text/javascript">
      ${'$'}(document).ready(function(){
        ${'$'}("#jquery_jplayer_1").jPlayer({
          ready: function () {
            ${'$'}(this).jPlayer("setMedia", {
              m4a: "https://media.soundgasm.net/sounds/19efb39a60acd22076b4bc7d95daedcae9734d0e.m4a"
            });
          },
          supplied: "m4a",
        });
      });
    </script>
    </body></html>
  """.trimIndent()

  @Test
  fun classifiesProfilesAndUploads() {
    val profile = ProfilePages.classify("http://www.soundgasm.net/u/someone/".toHttpUrl())
    assertTrue(profile is ProfilePages.Page.Profile)
    assertEquals("https://soundgasm.net/u/someone", profile!!.url.toString())
    assertEquals("someone", profile.user)

    val upload = ProfilePages.classify("https://soundgasm.net/u/someone/First-One?x=1".toHttpUrl())
    assertTrue(upload is ProfilePages.Page.Upload)
    assertEquals("https://soundgasm.net/u/someone/First-One", upload!!.url.toString())

    assertNull(ProfilePages.classify("https://soundgasm.net/".toHttpUrl()))
    assertNull(ProfilePages.classify("https://soundgasm.net/login".toHttpUrl()))
    assertNull(ProfilePages.classify("https://example.com/u/someone".toHttpUrl()))
  }

  @Test
  fun readsAProfilesUploads() {
    val profile = ProfilePages.classify("https://soundgasm.net/u/someone".toHttpUrl()) as ProfilePages.Page.Profile
    val folder = ProfilePages.parseProfile(profileHtml, profile)
    assertEquals("someone", folder.title)
    assertEquals(
      listOf("[Tag] First one", "Second"),
      folder.entries.map { it.label },
    )
    assertEquals(listOf("[Tag] First one.m4a", "Second.m4a"), folder.entries.map { it.name })
    assertTrue(folder.entries.all { it.type == RemoteEntry.Type.FILE })
    assertEquals("https://soundgasm.net/u/someone/First-One", WebRef.urlOf(folder.entries[0].id).toString())
  }

  @Test
  fun readsAnUploadsAudio() {
    val page = ProfilePages.classify("https://soundgasm.net/u/someone/Verification".toHttpUrl()) as ProfilePages.Page.Upload
    val audio = ProfilePages.parseUpload(uploadHtml, page)!!
    assertEquals("Verification", audio.title)
    assertEquals(
      "https://media.soundgasm.net/sounds/19efb39a60acd22076b4bc7d95daedcae9734d0e.m4a",
      audio.mediaUrl.toString(),
    )
  }

  @Test
  fun aRemovedUploadHasNoAudio() {
    val page = ProfilePages.classify("https://soundgasm.net/u/someone/Gone".toHttpUrl()) as ProfilePages.Page.Upload
    assertNull(ProfilePages.parseUpload("<html><body><p>Not found</p></body></html>", page))
  }
}
