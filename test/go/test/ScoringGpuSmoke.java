package go.test;

import go.ai.AiProfile;
import go.ai.KataGoEngine;
import go.core.Game;
import java.nio.file.Path;

/** Known settled position with one unremoved dead white stone and two black eyes. */
public final class ScoringGpuSmoke {
    public static void main(String[] args) throws Exception {
        Game game = new Game(9, 7.5);
        game.play(1, 1, 0);
        game.play(2, 0, 0);
        for (int y = 0; y < 9; y++) for (int x = 0; x < 9; x++) {
            if ((x == 0 && y <= 1) || (x == 1 && y == 0)
                    || (x == 4 && y == 4) || (x == 6 && y == 6)) continue;
            if (game.turn() == 2) game.pass(2);
            game.play(1, x, y);
        }
        // Both pass while the white stone is still physically on the board.
        game.pass(game.turn());
        game.pass(game.turn());
        Path home = KataGoEngine.home();
        try (KataGoEngine engine = KataGoEngine.create(KataGoEngine.executable(home),
                AiProfile.SMALL.model(home), home.resolve("gtp.cfg"), home, AiProfile.Budget.DEEP)) {
            KataGoEngine.Assessment result = engine.assess(game);
            if (!result.dead().get(0)) throw new AssertionError("Trapped white stone not identified as dead");
            if (Math.abs(result.blackLead() - 73.5) > 0.1)
                throw new AssertionError("Expected B+73.5 with 7.5 komi; got " + result.blackLead());
            System.out.println("PASS real GPU: unremoved dead white stone, two passes, black area 81 minus komi 7.5 = B+73.5");
        }
    }
}
