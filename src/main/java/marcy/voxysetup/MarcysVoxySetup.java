package marcy.voxysetup;

import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(MarcysVoxySetup.MOD_ID)
public class MarcysVoxySetup {
    public static final String MOD_ID = "marcy_voxy_setup";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public MarcysVoxySetup() {
        LOGGER.info("Marcy's Voxy Setup mod loaded.");
    }
}
