#!/usr/bin/env python3
"""Lumen Home (Lumen OS 1.0.6): the NEW strings of Files > Network folders (net/NetFolders: SMB shares of a
PC, a Mac or a NAS; the server form, its errors, the share list) in all 16 UI languages.

    python3 tools/i18n_home_i.py          validate only
    python3 tools/i18n_home_i.py --write  also render res/values-<locale>/strings.xml and the pending files

Same rules and output as i18n_home_f.py, whose run() it uses (gen.py's checks; gen.render() of each
values-<locale>/strings.xml from the translations already in it plus the ones below;
tools/i18n_pending_i/<locale>.txt in gen.py source format, section @home/strings.xml, for
merge_pending.py; lane apps/i18n/pending/home-i). One text per line below, in KEYS order. No long dash.
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
APP = os.path.dirname(HERE)
APPS = os.path.dirname(APP)
sys.path.insert(0, os.path.join(APPS, "i18n"))
import gen  # noqa: E402,F401
sys.path.insert(0, HERE)
import i18n_home_f  # noqa: E402  (its run(): the same checks and output)

PENDING = os.path.join(HERE, "i18n_pending_i")
KEYS = ["net_title", "net_sub", "net_hint", "net_add", "net_found", "net_address", "net_address_hint", "net_port",
        "net_login", "net_login_hint", "net_password", "net_share", "net_name", "net_not_set", "net_guest",
        "net_all_shares", "net_connect", "net_connecting", "net_enter_share", "net_no_shares", "net_need_password",
        "net_edit", "net_retry", "net_err_address", "net_err_logon", "net_err_denied", "net_err_share",
        "net_err_unreachable", "net_err_encrypt", "net_err_other"]


def rows(block):
    v = [ln.strip() for ln in block.strip("\n").split("\n")]
    assert len(v) == len(KEYS), (len(v), v[0])
    for t in v:
        assert "\u2014" not in t, t   # no long dash in user-visible text
    return dict(zip(KEYS, v))


T = {
    "ru": rows("""
Сетевые папки
Windows, Mac, Linux или NAS
Удерживайте OK на сервере, чтобы изменить или удалить его
Добавить сервер
В этой сети
Адрес
IP-адрес или имя
Порт
Логин
Пусто для гостевого доступа
Пароль
Общая папка
Название
Не задано
Гостевой доступ
Все общие папки
Подключиться
Подключение…
Ввести имя общей папки
Список общих папок недоступен. Введите имя общей папки.
Введите пароль для подключения
Изменить
Повторить
Введите адрес сервера
Неверный логин или пароль
Доступ запрещён
Общая папка не найдена
Сервер недоступен. Проверьте адрес и порт.
Сервер требует шифрование, оно не поддерживается
Не удалось подключиться: %1$s
"""),
    "uk": rows("""
Мережеві папки
Windows, Mac, Linux або NAS
Утримуйте OK на сервері, щоб змінити або видалити його
Додати сервер
У цій мережі
Адреса
IP-адреса або ім’я
Порт
Логін
Порожньо для гостьового доступу
Пароль
Спільна папка
Назва
Не задано
Гостьовий доступ
Усі спільні папки
Під’єднатися
Під’єднання…
Ввести ім’я спільної папки
Список спільних папок недоступний. Введіть ім’я спільної папки.
Введіть пароль для під’єднання
Змінити
Повторити
Введіть адресу сервера
Неправильний логін або пароль
Доступ заборонено
Спільну папку не знайдено
Сервер недоступний. Перевірте адресу й порт.
Сервер вимагає шифрування, воно не підтримується
Не вдалося під’єднатися: %1$s
"""),
    "be": rows("""
Сеткавыя папкі
Windows, Mac, Linux або NAS
Утрымлівайце OK на серверы, каб змяніць або выдаліць яго
Дадаць сервер
У гэтай сетцы
Адрас
IP-адрас або імя
Порт
Лагін
Пуста для гасцявога доступу
Пароль
Агульная папка
Назва
Не зададзена
Гасцявы доступ
Усе агульныя папкі
Падключыцца
Падключэнне…
Увесці імя агульнай папкі
Спіс агульных папак недаступны. Увядзіце імя агульнай папкі.
Увядзіце пароль для падключэння
Змяніць
Паўтарыць
Увядзіце адрас сервера
Няправільны лагін або пароль
Доступ забаронены
Агульная папка не знойдзена
Сервер недаступны. Праверце адрас і порт.
Сервер патрабуе шыфраванне, яно не падтрымліваецца
Не ўдалося падключыцца: %1$s
"""),
    "kk": rows("""
Желілік қалталар
Windows, Mac, Linux немесе NAS
Серверді өзгерту не жою үшін оның үстінде OK басып тұрыңыз
Сервер қосу
Осы желіде
Мекенжай
IP мекенжайы немесе атауы
Порт
Логин
Қонақ кіруі үшін бос қалдырыңыз
Құпиясөз
Ортақ қалта
Атауы
Орнатылмаған
Қонақ кіруі
Барлық ортақ қалталар
Қосылу
Қосылуда…
Ортақ қалта атын енгізу
Ортақ қалталар тізімі қолжетімсіз. Ортақ қалта атын енгізіңіз.
Қосылу үшін құпиясөзді енгізіңіз
Өзгерту
Қайталау
Сервер мекенжайын енгізіңіз
Логин немесе құпиясөз қате
Кіруге тыйым салынған
Ортақ қалта табылмады
Сервер қолжетімсіз. Мекенжай мен портты тексеріңіз.
Сервер шифрлауды талап етеді, ол қолдау көрсетілмейді
Қосылу мүмкін болмады: %1$s
"""),
    "de": rows("""
Netzwerkordner
Windows, Mac, Linux oder ein NAS
OK auf einem Server halten, um ihn zu bearbeiten oder zu löschen
Server hinzufügen
In diesem Netzwerk
Adresse
IP-Adresse oder Name
Port
Benutzername
Leer für Gastzugriff
Passwort
Freigabe
Name
Nicht festgelegt
Gastzugriff
Alle Freigaben
Verbinden
Verbindung wird hergestellt…
Freigabenamen eingeben
Die Liste der Freigaben ist nicht verfügbar. Gib einen Freigabenamen ein.
Gib das Passwort ein, um dich zu verbinden
Bearbeiten
Erneut versuchen
Gib die Serveradresse ein
Falscher Benutzername oder falsches Passwort
Zugriff verweigert
Freigabe nicht gefunden
Server nicht erreichbar. Prüfe Adresse und Port.
Dieser Server verlangt Verschlüsselung, die nicht unterstützt wird
Verbindung fehlgeschlagen: %1$s
"""),
    "fr": rows("""
Dossiers réseau
Windows, Mac, Linux ou un NAS
Maintenez OK sur un serveur pour le modifier ou le supprimer
Ajouter un serveur
Sur ce réseau
Adresse
Adresse IP ou nom
Port
Identifiant
Vide pour l’accès invité
Mot de passe
Partage
Nom
Non défini
Accès invité
Tous les partages
Se connecter
Connexion…
Saisir un nom de partage
La liste des partages n’est pas disponible. Saisissez un nom de partage.
Saisissez le mot de passe pour vous connecter
Modifier
Réessayer
Saisissez l’adresse du serveur
Identifiant ou mot de passe incorrect
Accès refusé
Partage introuvable
Serveur injoignable. Vérifiez l’adresse et le port.
Ce serveur exige le chiffrement, qui n’est pas pris en charge
Connexion impossible : %1$s
"""),
    "es": rows("""
Carpetas de red
Windows, Mac, Linux o un NAS
Mantén OK en un servidor para editarlo o eliminarlo
Añadir un servidor
En esta red
Dirección
Dirección IP o nombre
Puerto
Usuario
Vacío para acceso de invitado
Contraseña
Recurso compartido
Nombre
Sin definir
Acceso de invitado
Todos los recursos compartidos
Conectar
Conectando…
Escribir el nombre del recurso compartido
La lista de recursos compartidos no está disponible. Escribe el nombre del recurso compartido.
Escribe la contraseña para conectarte
Editar
Reintentar
Escribe la dirección del servidor
Usuario o contraseña incorrectos
Acceso denegado
Recurso compartido no encontrado
No se puede acceder al servidor. Comprueba la dirección y el puerto.
Este servidor exige cifrado, que no es compatible
No se pudo conectar: %1$s
"""),
    "it": rows("""
Cartelle di rete
Windows, Mac, Linux o un NAS
Tieni premuto OK su un server per modificarlo o eliminarlo
Aggiungi un server
In questa rete
Indirizzo
Indirizzo IP o nome
Porta
Nome utente
Vuoto per l’accesso ospite
Password
Condivisione
Nome
Non impostato
Accesso ospite
Tutte le condivisioni
Connetti
Connessione…
Inserisci il nome della condivisione
L’elenco delle condivisioni non è disponibile. Inserisci il nome della condivisione.
Inserisci la password per connetterti
Modifica
Riprova
Inserisci l’indirizzo del server
Nome utente o password errati
Accesso negato
Condivisione non trovata
Server non raggiungibile. Controlla indirizzo e porta.
Questo server richiede la crittografia, che non è supportata
Impossibile connettersi: %1$s
"""),
    "pt": rows("""
Pastas de rede
Windows, Mac, Linux ou um NAS
Mantenha OK pressionado em um servidor para editá-lo ou excluí-lo
Adicionar um servidor
Nesta rede
Endereço
Endereço IP ou nome
Porta
Usuário
Vazio para acesso de convidado
Senha
Compartilhamento
Nome
Não definido
Acesso de convidado
Todos os compartilhamentos
Conectar
Conectando…
Digitar o nome do compartilhamento
A lista de compartilhamentos não está disponível. Digite o nome do compartilhamento.
Digite a senha para conectar
Editar
Tentar novamente
Digite o endereço do servidor
Usuário ou senha incorretos
Acesso negado
Compartilhamento não encontrado
Servidor inacessível. Verifique o endereço e a porta.
Este servidor exige criptografia, que não é compatível
Não foi possível conectar: %1$s
"""),
    "pl": rows("""
Foldery sieciowe
Windows, Mac, Linux lub NAS
Przytrzymaj OK na serwerze, aby go edytować lub usunąć
Dodaj serwer
W tej sieci
Adres
Adres IP lub nazwa
Port
Login
Puste dla dostępu gościa
Hasło
Udział
Nazwa
Nie ustawiono
Dostęp gościa
Wszystkie udziały
Połącz
Łączenie…
Wpisz nazwę udziału
Lista udziałów jest niedostępna. Wpisz nazwę udziału.
Wpisz hasło, aby się połączyć
Edytuj
Spróbuj ponownie
Wpisz adres serwera
Nieprawidłowy login lub hasło
Brak dostępu
Nie znaleziono udziału
Serwer jest nieosiągalny. Sprawdź adres i port.
Ten serwer wymaga szyfrowania, które nie jest obsługiwane
Nie udało się połączyć: %1$s
"""),
    "tr": rows("""
Ağ klasörleri
Windows, Mac, Linux veya bir NAS
Bir sunucuyu düzenlemek veya silmek için üzerinde OK’e basılı tutun
Sunucu ekle
Bu ağda
Adres
IP adresi veya ad
Bağlantı noktası
Kullanıcı adı
Misafir erişimi için boş bırakın
Parola
Paylaşım
Ad
Ayarlanmadı
Misafir erişimi
Tüm paylaşımlar
Bağlan
Bağlanıyor…
Paylaşım adı girin
Paylaşım listesi kullanılamıyor. Bir paylaşım adı girin.
Bağlanmak için parolayı girin
Düzenle
Yeniden dene
Sunucu adresini girin
Kullanıcı adı veya parola yanlış
Erişim reddedildi
Paylaşım bulunamadı
Sunucuya ulaşılamıyor. Adresi ve bağlantı noktasını kontrol edin.
Bu sunucu şifreleme gerektiriyor, bu desteklenmiyor
Bağlanılamadı: %1$s
"""),
    "zh-rCN": rows("""
网络文件夹
Windows、Mac、Linux 或 NAS
在服务器上长按 OK 可编辑或删除
添加服务器
此网络中
地址
IP 地址或名称
端口
用户名
留空则以访客身份访问
密码
共享文件夹
名称
未设置
访客访问
所有共享文件夹
连接
正在连接…
输入共享文件夹名称
无法获取共享文件夹列表。请输入共享文件夹名称。
输入密码以连接
编辑
重试
请输入服务器地址
用户名或密码错误
访问被拒绝
未找到共享文件夹
无法访问服务器。请检查地址和端口。
此服务器要求加密，目前不支持
无法连接：%1$s
"""),
    "zh-rTW": rows("""
網路資料夾
Windows、Mac、Linux 或 NAS
在伺服器上長按 OK 即可編輯或刪除
新增伺服器
此網路中
位址
IP 位址或名稱
連接埠
使用者名稱
留空則以訪客身分存取
密碼
共用資料夾
名稱
未設定
訪客存取
所有共用資料夾
連線
正在連線…
輸入共用資料夾名稱
無法取得共用資料夾清單。請輸入共用資料夾名稱。
輸入密碼以連線
編輯
重試
請輸入伺服器位址
使用者名稱或密碼錯誤
存取遭拒
找不到共用資料夾
無法連上伺服器。請檢查位址和連接埠。
此伺服器要求加密，目前不支援
無法連線：%1$s
"""),
    "ja": rows("""
ネットワークフォルダー
Windows、Mac、Linux、NAS
サーバーで OK を長押しすると編集・削除できます
サーバーを追加
このネットワーク上
アドレス
IP アドレスまたは名前
ポート
ユーザー名
空欄でゲストアクセス
パスワード
共有フォルダー
名前
未設定
ゲストアクセス
すべての共有フォルダー
接続
接続しています…
共有フォルダー名を入力
共有フォルダーの一覧を取得できません。共有フォルダー名を入力してください。
接続するにはパスワードを入力してください
編集
再試行
サーバーのアドレスを入力してください
ユーザー名またはパスワードが違います
アクセスが拒否されました
共有フォルダーが見つかりません
サーバーに接続できません。アドレスとポートを確認してください。
このサーバーは暗号化が必要ですが、対応していません
接続できませんでした：%1$s
"""),
    "ko": rows("""
네트워크 폴더
Windows, Mac, Linux 또는 NAS
서버에서 OK를 길게 누르면 수정하거나 삭제할 수 있습니다
서버 추가
이 네트워크
주소
IP 주소 또는 이름
포트
사용자 이름
게스트 액세스는 비워 두세요
비밀번호
공유 폴더
이름
설정 안 됨
게스트 액세스
모든 공유 폴더
연결
연결 중…
공유 폴더 이름 입력
공유 폴더 목록을 가져올 수 없습니다. 공유 폴더 이름을 입력하세요.
연결하려면 비밀번호를 입력하세요
수정
다시 시도
서버 주소를 입력하세요
사용자 이름 또는 비밀번호가 잘못되었습니다
액세스가 거부되었습니다
공유 폴더를 찾을 수 없습니다
서버에 연결할 수 없습니다. 주소와 포트를 확인하세요.
이 서버는 암호화가 필요하지만 지원되지 않습니다
연결할 수 없음: %1$s
"""),
    "ar": rows("""
مجلدات الشبكة
Windows أو Mac أو Linux أو NAS
اضغط مع الاستمرار على OK فوق خادم لتعديله أو حذفه
إضافة خادم
على هذه الشبكة
العنوان
عنوان IP أو الاسم
المنفذ
اسم المستخدم
اتركه فارغًا للدخول كضيف
كلمة المرور
المجلد المشترك
الاسم
غير محدد
دخول الضيف
كل المجلدات المشتركة
اتصال
جارٍ الاتصال…
إدخال اسم المجلد المشترك
قائمة المجلدات المشتركة غير متاحة. أدخل اسم المجلد المشترك.
أدخل كلمة المرور للاتصال
تعديل
إعادة المحاولة
أدخل عنوان الخادم
اسم المستخدم أو كلمة المرور غير صحيحة
تم رفض الوصول
لم يتم العثور على المجلد المشترك
تعذّر الوصول إلى الخادم. تحقق من العنوان والمنفذ.
يتطلب هذا الخادم التشفير، وهو غير مدعوم
تعذّر الاتصال: %1$s
"""),
}


def main():
    return i18n_home_f.run(KEYS, T, PENDING, "1.0.6 Files: network folders")


if __name__ == "__main__":
    sys.exit(main())
