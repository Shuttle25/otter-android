package app.ottermsg.feature.chats

import app.ottermsg.core.data.invite.NoInviteRightsException
import app.ottermsg.core.data.invite.NotConnectedException
import org.junit.Assert.assertEquals
import org.junit.Test

class InviteViewModelTest {
    @Test fun withdrawnRightsAreNotARetryableError() {
        assertEquals(InviteUiState.NoRights, InviteViewModel.failure(NoInviteRightsException()))
        assertEquals(InviteUiState.Error, InviteViewModel.failure(NotConnectedException()))
        assertEquals(InviteUiState.Error, InviteViewModel.failure(IllegalStateException("no manifest")))
    }
}
