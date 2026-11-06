// scene object graph -- pseudo-code view of the captured hierarchy

scene TitleScene
{
    GameObject "Canvas/MapSceneButton"   // Canvas[2]/MapSceneButton[1]
    {
        Image.sprite = Sprite_Start_Button_0;
        Button.onClick += TitleSceneManager.InitPlayerData;   // target TitleSceneController
    }
    GameObject "Canvas/continue"   // Canvas[2]/continue[2]
    {
        Image.sprite = KakaoTalk_20240703_211209276_0;
        Button.onClick += TitleSceneManager.LoadStoryScene;   // target TitleSceneController
    }
    GameObject "Canvas/ExitButton"   // Canvas[2]/ExitButton[3]
    {
        Image.sprite = Sprite_Exit_Button_0;
        Button.onClick += TitleSceneManager.QuitGame;   // target TitleSceneController
    }
    GameObject "TitleSceneController"   // TitleSceneController[4]
    {
        GameObject TitleSceneManager.continueButton = "continue";   // Canvas/continue
    }
    GameObject "SaveLoadController"   // SaveLoadController[5]
    {
        SaveLoadController;
    }
}

scene StoryScene
{
    GameObject "StoryController"   // StoryController[3]
    {
        AudioSource StoryController.audioSource = "BackgroundMusic";   // BackgroundMusic
        GameObject StoryController.backgorunds = "Background1";   // Background1
        GameObject StoryController.backgorunds = "Background2";   // Background2
        AudioClip StoryController.badMood = "05a Battle Theme";   // asset
        ChatWindowController StoryController.chatWindowController = "ChatWindow";   // Canvas/ChatWindow
        ChatWindowScriptContainer StoryController.scriptContainer = "StoryScript";   // asset
    }
    GameObject "Canvas/ChatWindow"   // Canvas[4]/ChatWindow[0]
    {
        TextMeshProUGUI.observed-text = 아무 키나 누르세요;
        GameObject ChatWindowController.anyKeyPrompt = "AnyKeyPrompt";   // Canvas/ChatWindow/AnyKeyPrompt
    }
}

scene Map_scene
{
    GameObject "MapScene"   // MapScene[1]
    {
        GameObject MapMove.background = "Background";   // Background
        GameObject MapMove.battle1 = "battle1";   // battle1
        GameObject MapMove.battle2 = "battle2";   // battle2
        GameObject MapMove.battle3 = "battle3";   // battle3
        GameObject MapMove.boss = "boss";   // boss
        GameObject MapMove.character = "wordHead";   // wordHead
        TextMeshProUGUI MapMove.stage = "Stage";   // Canvas/Stage
        Sprite MapMove.stage1 = "Stage1BG";   // asset
        Sprite MapMove.stage2 = "Stage2BG";   // asset
        Sprite MapMove.stage3 = "Stage3BG";   // asset
        Sprite MapMove.stage4 = "Stage4BG";   // asset
        GameObject MapMove.village = "village";   // village
    }
    GameObject "Canvas/Button (Legacy)"   // Canvas[7]/Button (Legacy)[0]
    {
        Image.sprite = KakaoTalk_Photo_2024-07-03-15-48-40;
        Button.onClick += BackButton.BackToMain;   // target Canvas/Button (Legacy)
        BackButton;
    }
    GameObject "SaveLoadController"   // SaveLoadController[9]
    {
        SaveLoadController;
    }
    GameObject "GameObject"   // GameObject[11]
    {
        StageDataSingleton;
    }
    GameObject "TutorialController"   // TutorialController[14]
    {
        TextMeshProUGUI.observed-text = 아무 키나 누르세요;
        GameObject TutorialController.inputBlocker = "InputBlocker";   // TutorialController/InputBlocker
        TutorialChatWindow TutorialController.tutorialChatWindow = "ChatWindow";   // TutorialController/ChatWindow
        TutorialScriptContainer TutorialController.tutorialScript = "TutorialScript";   // asset
    }
    GameObject "TutorialController/ChatWindow"   // TutorialController[14]/ChatWindow[1]
    {
        TextMeshProUGUI.observed-text = 아무 키나 누르세요;
        GameObject TutorialChatWindow.anyKeyPrompt = "AnyKeyPrompt";   // TutorialController/ChatWindow/AnyKeyPrompt
        Image TutorialChatWindow.speakerImage = "SpeakerImage";   // TutorialController/ChatWindow/SpeakerImage
    }
}

scene GameClearScene
{
    GameObject "GameClearBackground"   // GameClearBackground[1]
    {
        SpriteRenderer.sprite = GameClearBackground;
        GameObject GameClearController.magicCard = "TypeCard";   // asset carries Card, Order, DraggableCard, TextMeshPro, TMP_Text
        GameObject GameClearController.spellCard = "MagicCard";   // asset carries Card, Order, DraggableCard, TextMeshPro, TMP_Text
        GameObject GameClearController.text = "Congratulation";   // Congratulation
        WordScriptableObject GameClearController.wordSo = "WordList";   // asset
    }
}

scene GameOverScene
{
    GameObject "GameoverBackGround"   // GameoverBackGround[1]
    {
        SpriteRenderer.sprite = KakaoTalk_20240630_190136908;
        GameClearController;
    }
}

scene TurnBattleScene
{
    GameObject "Manager"   // Manager[2]
    {
        GameObject EnemyTestManager.player = "Word";   // Word
        EnemyDataContainer EnemyPoolController.enemyDataContainer = "EnemyDataContainer";   // asset
        BattleScriptContainer BattleWaveController.battleScript = "PlainBattleWaveData";   // asset
        AudioClip StageManager.audioClips = "Pixel 1";   // asset
        AudioClip StageManager.audioClips = "Pixel 2";   // asset
        AudioClip StageManager.audioClips = "Pixel 3";   // asset
        AudioClip StageManager.audioClips = "Pixel 4";   // asset
        AudioClip StageManager.audioClips = "Pixel 5";   // asset
        AudioSource StageManager.audioSource = "BackgroundMusic";   // BackgroundMusic
        GameObject StageManager.rainyBackground = "RainyBackground";   // RainyBackground
        StageData StageManager.stageDataList = "PlainStage";   // asset
        StageData StageManager.stageDataList = "SeaStage";   // asset
        StageData StageManager.stageDataList = "HighlandStage";   // asset
        StageData StageManager.stageDataList = "RainStage";   // asset
        StageData StageManager.stageDataList = "BossStage";   // asset
    }
    GameObject "DebugCanvas/TurnEndButton"   // DebugCanvas[4]/TurnEndButton[0]
    {
        Image.sprite = turnendButton_0;
        Button.onClick += TurnBattleSystem.TurnEndButton;   // target TurnBattleSystem
    }
    GameObject "TurnBattleSystem"   // TurnBattleSystem[5]
    {
        CardManager TurnBattleSystem.cardManager = "CardManager";   // CardSystem/CardManager
        EnemyTestManager TurnBattleSystem.enemyManager = "Manager";   // Manager
        EnemyPoolController TurnBattleSystem.enemyPoolController = "Manager";   // Manager
    }
    GameObject "CardSystem/CardManager"   // CardSystem[6]/CardManager[3]
    {
        Transform CardManager.cardLeft = "CardLeft";   // CardSystem/CardLeft
        GameObject CardManager.cardPrefab = "Card";   // asset carries Card, Order, DraggableCard, TextMeshPro, TMP_Text
        Transform CardManager.cardRight = "CardRight";   // CardSystem/CardRight
        Transform CardManager.cardSpawnPoint = "CardSpawnPoint";   // CardSystem/CardSpawnPoint
        GameObject CardManager.pushArea1 = "Zone1";   // CombineSystem/CombineZone/Zone1
        GameObject CardManager.pushArea2 = "Zone2";   // CombineSystem/CombineZone/Zone2
        WordScriptableObject CardManager.wordSo = "WordList";   // asset
    }
    GameObject "CombineSystem/CombineButton"   // CombineSystem[7]/CombineButton[0]
    {
        Image.sprite = Sprite_CombineButton_0;
        Button.onClick += CombineButton.OnButtonClick;   // target CombineSystem/CombineButton
        Button CombineButton.activateButton = "CombineButton";   // CombineSystem/CombineButton
        GameObject CombineButton.combineZone = "CombineZone";   // CombineSystem/CombineZone
    }
    GameObject "CombineSystem/CombineZone"   // CombineSystem[7]/CombineZone[1]
    {
        Image.sprite = Sprite_combineZone_0;
        TextMeshProUGUI.control-caption = Combine;
        Button CombineZone.activateButton = "Button";   // CombineSystem/CombineZone/Button
        GameObject CombineZone.drop = "Drop";   // Drop
        MagicAffinityTable CombineZone.magicAffinityTable = "Magic Affinity Table";   // asset
        AudioSource CombineZone.magicEffectSource = "MusicEffectSource";   // MusicEffectSource
        GameObject CombineZone.shoot = "Shoot";   // Shoot
        GameObject CombineZone.summon = "Summon";   // Summon
    }
    GameObject "CombineSystem/CombineZone/Zone1"   // CombineSystem[7]/CombineZone[1]/Zone1[0]
    {
        CombineZone DropZone.combineZone = "CombineZone";   // CombineSystem/CombineZone
        DropZone;
    }
    GameObject "CombineSystem/CombineZone/Zone2"   // CombineSystem[7]/CombineZone[1]/Zone2[1]
    {
        CombineZone DropZone.combineZone = "CombineZone";   // CombineSystem/CombineZone
        DropZone;
    }
    GameObject "CombineSystem/CombineZone/Button"   // CombineSystem[7]/CombineZone[1]/Button[2]  [inactive]
    {
        TextMeshProUGUI.control-caption = Combine;
        Button.onClick += CombineZone.OnButtonClick;   // target CombineSystem/CombineZone
    }
    GameObject "Word"   // Word[12]
    {
        SpriteRenderer.sprite = KakaoTalk_20240701_192204698_22;
        SpriteRenderer.sprite = Red Potion 3;
        TextMeshPro.observed-text = 1;
        SelectableObject;
        Player;
    }
}

scene EndingScene
{
    GameObject "StoryController"   // StoryController[1]
    {
        AudioSource StoryController.audioSource = "BackgroundMusic";   // BackgroundMusic
        GameObject StoryController.backgorunds = "Background 6 (Bonus)";   // Background 6 (Bonus)
        AudioClip StoryController.badMood = "05a Battle Theme";   // asset
        ChatWindowController StoryController.chatWindowController = "ChatWindow";   // Canvas/ChatWindow
        ChatWindowScriptContainer StoryController.scriptContainer = "EndingScript";   // asset
    }
    GameObject "Canvas/ChatWindow"   // Canvas[2]/ChatWindow[0]
    {
        TextMeshProUGUI.observed-text = 아무 키나 누르세요;
        GameObject ChatWindowController.anyKeyPrompt = "AnyKeyPrompt";   // Canvas/ChatWindow/AnyKeyPrompt
    }
}
