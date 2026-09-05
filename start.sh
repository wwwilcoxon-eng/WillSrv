#!/bin/sh
exec java -Xms8G -Xmx12G -Dcom.mojang.eula.agree=true -jar server.jar --nogui --bonusChest
